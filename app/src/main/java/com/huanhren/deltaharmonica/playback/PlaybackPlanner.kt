package com.huanhren.deltaharmonica.playback

import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.model.*
import com.huanhren.deltaharmonica.input.MAX_GESTURE_DURATION_MS
import kotlin.math.roundToLong

data class PlannedEvent(val sourceIndex: Int, val requestedStartMs: Long, val predictedStartMs: Long, val predictedEndMs: Long,
    val regionSwitch: Boolean, val semitoneSwitch: Boolean, val event: ScriptEvent)
data class PlaybackPlan(val events: List<PlannedEvent>, val regionSwitches: Int, val semitoneSwitches: Int,
    val estimatedTotalMs: Long, val warnings: List<String>, val fatalErrors: List<String>, val controlGestureMs: Long, val settlingMs: Long, val combined: Boolean = false,
    val minimumTouchMs: Long = 1, val extendedNotes: Int = 0, val combinedLeadMs: Long = 40, val gestureOverheadMs: Long = 30,
    val rootTiming: RootPlaybackTiming? = null, val shortenedNotes: Int = 0, val rootPreRollMs: Long = 0,
    val rootTimingConflicts: List<RootTimingConflict> = emptyList())

/** A note whose score timing had to change to fit the physical Root touch constraints. */
data class RootTimingConflict(
    val sourceIndex: Int,
    val availableMs: Long,
    val requiredMs: Long,
    val constraint: Constraint,
    val repeatedKey: Boolean = false,
    val controlSetupMs: Long = 0,
) {
    enum class Constraint { NOTE_HOLD, TRANSITION }

    internal val deficitMs: Long get() = requiredMs - availableMs
}

object PlaybackPlanner {
    fun mappedEventCount(script: PerformanceScript, layout: InstrumentLayout): Int {
        val profile=layout.profile ?: return 0
        if (!ScriptConverter.matchesProfile(script,profile,layout)) return 0
        val keys = PlaybackMapping.noteKeys(layout)
        return script.events.count { PlaybackMapping.isMapped(it, profile, keys) }
    }
    /** Conservative placeholders, not measured device latency. Explicitly stretch, never silently skip notes. */
    fun plan(script: PerformanceScript, layout: InstrumentLayout, initialRegion: Region?, initialSemitone: Boolean?,
             start: Int, count: Int, speed: Double, controlMs: Long = 150, settleMs: Long = 100, combined: Boolean = false,
             minimumTouchMs: Long = 1, combinedLeadMs: Long = 40, gestureOverheadMs: Long = 30,
             rootTiming: RootPlaybackTiming? = null,
             checkActive: () -> Unit = {}): PlaybackPlan {
        checkActive()
        require(count in 1..100_000 && start >= 0 && start.toLong() + count <= script.events.size && speed in setOf(.5,.75,1.0,2.0))
        require(controlMs in 1..2000 && settleMs in 0..2000)
        require(minimumTouchMs in 1..500)
        require(combinedLeadMs in 0..79)
        require(gestureOverheadMs in 0..500)
        require(rootTiming == null || !combined)
        val warnings = mutableListOf<String>(); val errors = mutableListOf<String>()
        val profile = layout.profile
        if (script.schemaVersion !in 1..2) errors.add("不支持的脚本版本")
        if (profile == null || !ScriptConverter.matchesProfile(script,profile,layout)) errors.add("乐器配置或键位映射不匹配")
        if (initialSemitone == null) errors.add("半音状态未知，请先确认游戏中的半音开关")
        if (layout.referenceDisplay == null) errors.add("布局没有已校准的参考屏幕")
        if (script.schemaVersion == 1 || script.provenance == null) { warnings.add("旧脚本的转换历史未知，需重新转换"); errors.add("无法验证旧脚本是否存在未映射音符") }
        script.provenance?.let { p ->
            warnings.addAll(p.warnings)
            if (p.importStatus != "CLEAN") warnings.add("MIDI 导入状态：${p.importStatus}；请检查来源警告")
            if (p.discardedMs > 0) warnings.add("单音化裁剪 ${p.discardedMs} ms")
            if (p.unmappedCount > 0) errors.add("脚本有 ${p.unmappedCount} 个未映射音符，不能真实播放")
        }
        if (!script.mappingVerified) warnings.add("音高映射未经实机验证")
        if (combined) warnings.add("组合触摸的游戏生效顺序尚需实机验证；请先试听并核对音高")
        if (rootTiming != null) warnings.add("Root 连续触控使用待实测的 Android 时序，请先试听并核对音高与节奏")
        if (runCatching { ScriptCodec.validate(script) }.isFailure) errors.add("脚本数据无效")
        checkActive()
        // Reject extreme values before subtraction, scaling, or accumulating durations can overflow.
        // The bound reserves ample headroom for all 100,000 control and gesture durations.
        val timeLimit = Long.MAX_VALUE / 8
        val unsafeTime = script.events.any { it.startTimeMs > timeLimit || it.durationMs > timeLimit } ||
            (script.events.last().startTimeMs.toDouble() / speed) > timeLimit.toDouble()
        if (unsafeTime) errors.add("脚本时间超出安全计算范围，请重新转换")
        if (unsafeTime || "脚本数据无效" in errors)
            return PlaybackPlan(emptyList(),0,0,0,warnings.distinct(),errors.distinct(),controlMs,settleMs,combined,minimumTouchMs,0,combinedLeadMs,gestureOverheadMs,rootTiming)
        val targetById = layout.targets.associateBy { it.id }
        val noteKeys = PlaybackMapping.noteKeys(layout)
        val firstInvalid = script.events.indexOfFirst { !PlaybackMapping.isMapped(it, profile, noteKeys) }
        if (firstInvalid >= 0) errors.add("脚本第 ${firstInvalid+1} 个事件的目标或音高映射无效")
        val base = script.events[start].startTimeMs
        var currentRegion = initialRegion; var currentSemi = initialSemitone; var cursor=0L; var nr=0; var ns=0
        val original=script.events.subList(start,start+count)
        val requestedStarts = LongArray(count) {
            val relative = original[it].startTimeMs - base
            if (rootTiming == null) (relative / speed).roundToLong() else scaleRootTimestamp(relative, speed)
        }
        // Rounding both edges on one clock preserves a shared note-off/next note-on boundary.
        val rootHolds = rootTiming?.let {
            LongArray(count) { index ->
                val event = original[index]
                scaleRootTimestamp(event.startTimeMs - base + event.durationMs, speed) - requestedStarts[index]
            }
        }
        val keys = noteKeys.toSortedMap().entries.distinctBy { it.value }.associate { (id, key) -> key to id }
        val rootTransition: ((Int?, Int, PitchChoice?, PitchChoice) -> Long)? = rootTiming?.let { timing ->
            { previousIndex, index, previous, next ->
                val previousRegion = previous?.region ?: initialRegion
                val previousSemi = previous?.semitone ?: initialSemitone
                val setup = (if (previousRegion != next.region) timing.controlSlotMs else 0L) +
                    (if (previousSemi != next.semitone) timing.controlSlotMs else 0L)
                if (previousIndex == null) setup else {
                    val available = requestedStarts[index] - requestedStarts[previousIndex]
                    val availableHold = available - setup - timing.plannedGap(previous?.keyIndex == next.keyIndex, setup)
                    val preferredHold = requireNotNull(rootHolds)[previousIndex]
                        .coerceIn(timing.minNoteHoldMs, MAX_GESTURE_DURATION_MS)
                    val delay = (timing.minNoteHoldMs - availableHold).coerceAtLeast(0L)
                    val clipped = (preferredHold - maxOf(availableHold, timing.minNoteHoldMs)).coerceAtLeast(0L)
                    delay * 2 + clipped
                }
            }
        }
        val choices = profile?.let {
            PitchMapper.optimize(it, original.map { event -> event.midiNote }, initialRegion, initialSemitone,
                keys.keys, checkActive, transitionDelayMs = rootTransition)
        }
        var delayedCount=0; var maxDelay=0L; var extendedNotes=0
        var shortenedNotes = 0
        var shortenedMs = 0L
        var preRoll = 0L
        var blockedTransitions = 0
        var blockedRepeats = 0
        var blockedControlChanges = 0
        val rootConflicts = mutableListOf<RootTimingConflict>()
        var largestDeficit: RootTimingConflict? = null
        val minHold = rootTiming?.minNoteHoldMs ?: minimumTouchMs
        val plans = ArrayList<PlannedEvent>(count)
        original.forEachIndexed { index, event ->
            if (index % 1024 == 0) checkActive()
            val choice = choices?.get(index)
            val keyId = choice?.let { keys.getValue(it.keyIndex) }
            val e = if (choice == null || (event.noteKeyId == keyId && event.pitchRegion == choice.region &&
                    event.semitoneEnabled == choice.semitone)) event
                else event.copy(noteKeyId = requireNotNull(keyId), pitchRegion = choice.region, semitoneEnabled = choice.semitone)
            val target = targetById[e.noteKeyId]
            if (!PlaybackMapping.isMapped(e, profile, noteKeys))
                errors.add("事件 ${start+index+1} 的目标或音高映射无效")
            val changeRegion=currentRegion != e.pitchRegion; val changeSemi=currentSemi != e.semitoneEnabled
            if (changeRegion) { nr++; if (targetById["REGION_${e.pitchRegion.name}"]?.type != TargetType.REGION) errors.add("音区目标缺失：${e.pitchRegion}") }
            if (changeSemi) { ns++; if (targetById["SEMITONE_TOGGLE"]?.type != TargetType.TOGGLE) errors.add("半音目标缺失") }
            if ((combined || rootTiming != null) && target != null) {
                val positions=buildList {
                    add(target.position)
                    if (changeRegion) targetById["REGION_${e.pitchRegion.name}"]?.let { add(it.position) }
                    if (changeSemi) targetById["SEMITONE_TOGGLE"]?.let { add(it.position) }
                }
                if (positions.distinct().size != positions.size) errors.add("事件 ${start+index+1} 的触摸目标重合，请重新校准")
            }
            val requested = requestedStarts[index]
            val scaledDuration = rootHolds?.get(index) ?: (e.durationMs / speed).roundToLong()
            if (scaledDuration < minHold) {
                extendedNotes++
                if (rootTiming != null) {
                    rootConflicts.add(RootTimingConflict(start + index, scaledDuration, minHold,
                        RootTimingConflict.Constraint.NOTE_HOLD))
                }
            }
            val duration=scaledDuration.coerceAtLeast(minHold)
            val maxDuration=MAX_GESTURE_DURATION_MS - if(combined && (changeRegion || changeSemi)) combinedLeadMs else 0
            if (duration !in 1..maxDuration) errors.add("事件 ${start+index+1} 时长 $duration ms 超出手势范围，最大 $maxDuration ms")
            val before = when {
                rootTiming != null -> (if (changeRegion) rootTiming.controlSlotMs else 0L) +
                    (if (changeSemi) rootTiming.controlSlotMs else 0L)
                combined -> if (changeRegion || changeSemi) combinedLeadMs else 0L
                else -> (if (changeRegion) controlMs + settleMs + gestureOverheadMs else 0L) +
                    (if (changeSemi) controlMs + settleMs + gestureOverheadMs else 0L)
            }
            if (rootTiming != null && index == 0) preRoll = before
            val desired = requested + preRoll
            if (rootTiming != null && index > 0) {
                val previous = plans.last()
                val sameKey = previous.event.noteKeyId == e.noteKeyId
                val gap = rootTiming.plannedGap(sameKey, before)
                val available = requested - previous.requestedStartMs
                val required = minHold + before + gap
                if (available < required) {
                    val conflict = RootTimingConflict(start + index, available, required,
                        RootTimingConflict.Constraint.TRANSITION, repeatedKey = sameKey, controlSetupMs = before)
                    rootConflicts.add(conflict)
                    blockedTransitions++
                    if (sameKey) blockedRepeats++
                    if (before > 0L) blockedControlChanges++
                    if (conflict.deficitMs > (largestDeficit?.deficitMs ?: 0L)) {
                        largestDeficit = conflict
                    }
                }
                val end = minOf(previous.predictedEndMs, maxOf(previous.predictedStartMs + minHold, desired - before - gap))
                if (end < previous.predictedEndMs) {
                    shortenedNotes++
                    shortenedMs += previous.predictedEndMs - end
                    plans[index - 1] = previous.copy(predictedEndMs = end)
                }
                cursor = end + gap
            }
            val begin=maxOf(desired,cursor+before); val end=begin+duration.coerceAtMost(MAX_GESTURE_DURATION_MS)
            if (begin>desired) { delayedCount++; maxDelay=maxOf(maxDelay,begin-desired) }
            cursor = if (rootTiming != null) end
                else (if (combined && (changeRegion || changeSemi)) maxOf(end,begin+80-combinedLeadMs) else end) + gestureOverheadMs
            currentRegion=e.pitchRegion; currentSemi=e.semitoneEnabled
            plans.add(PlannedEvent(start+index,requested,begin,end,changeRegion,changeSemi,e))
        }
        if (delayedCount > 0) warnings.add("时间预算不足：$delayedCount 个事件需延迟，最大预计延迟 $maxDelay ms；时间轴已拉伸")
        if (rootTiming != null) {
            val originalEnd = requestedStarts.last() + requireNotNull(rootHolds).last()
            val stretch = (cursor - preRoll - originalEnd).coerceAtLeast(0L)
            if (preRoll > 0) warnings.add("首音前预留 $preRoll ms 切换控制状态")
            largestDeficit?.let { worst ->
                warnings.add("Root 有 $blockedTransitions 处起音间隔不足，涉及同键 $blockedRepeats 处、控制切换 $blockedControlChanges 处。" +
                    "第 ${worst.sourceIndex + 1} 个音符的起音间隔最紧，原谱仅给 ${worst.availableMs} ms，至少需要 ${worst.requiredMs} ms")
            }
            if (stretch > 0L) warnings.add("Root 预计比所选速度的原谱延长 $stretch ms")
            if (stretch > maxOf(2_000L, originalEnd * 2L)) {
                errors.add("当前速度的触控量超出时序预算，请降低倍速或换用较稀疏的曲谱")
            }
        }
        if (rootTiming == null && gestureOverheadMs > 0) warnings.add("每次手势额外预留 ${gestureOverheadMs} ms（估算，非设备实测）；休止时间可吸收，实际节奏可能变慢")
        if (extendedNotes > 0) {
            val examples = rootConflicts.asSequence().filter { it.constraint == RootTimingConflict.Constraint.NOTE_HOLD }
                .take(4).joinToString("、") { (it.sourceIndex + 1).toString() }
            val indices = if (examples.isEmpty()) "" else "（音符序号示例：$examples）"
            warnings.add("$extendedNotes 个短音按最短触摸 $minHold ms 延长$indices；后续音符可能推迟")
        }
        if (shortenedNotes > 0) warnings.add("$shortenedNotes 个音符为后续控制或重触发缩短，共 $shortenedMs ms")
        val unverifiedShort = plans.count { it.predictedEndMs - it.predictedStartMs < 150L }
        if (unverifiedShort > 0) warnings.add("$unverifiedShort 个音符短于此前已验证的 150 ms，真实响应尚未验证")
        return PlaybackPlan(plans, nr, ns, cursor, warnings.distinct(), errors.distinct(),
            rootTiming?.controlHoldMs ?: controlMs, rootTiming?.let { it.controlSlotMs - it.controlHoldMs } ?: settleMs,
            combined, minHold, extendedNotes, combinedLeadMs, if (rootTiming == null) gestureOverheadMs else 0,
            rootTiming, shortenedNotes, preRoll, rootConflicts)
    }

    // The checked script time limit leaves room to scale a timestamp's numerator before dividing.
    private fun scaleRootTimestamp(timeMs: Long, speed: Double): Long = when (speed) {
        0.5 -> timeMs * 2L
        0.75 -> (timeMs * 4L + 1L) / 3L
        1.0 -> timeMs
        2.0 -> (timeMs + 1L) / 2L
        else -> error("Unsupported playback speed: $speed")
    }
}
