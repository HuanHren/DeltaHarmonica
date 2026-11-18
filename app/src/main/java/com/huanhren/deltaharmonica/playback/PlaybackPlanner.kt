package com.huanhren.deltaharmonica.playback

import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.model.*
import com.huanhren.deltaharmonica.input.MAX_GESTURE_DURATION_MS
import kotlin.math.roundToLong

data class PlannedEvent(val sourceIndex: Int, val requestedStartMs: Long, val predictedStartMs: Long, val predictedEndMs: Long,
    val regionSwitch: Boolean, val semitoneSwitch: Boolean, val event: ScriptEvent)
data class PlaybackPlan(val events: List<PlannedEvent>, val regionSwitches: Int, val semitoneSwitches: Int,
    val estimatedTotalMs: Long, val warnings: List<String>, val fatalErrors: List<String>, val controlGestureMs: Long, val settlingMs: Long, val combined: Boolean = false,
    val minimumTouchMs: Long = 1, val extendedNotes: Int = 0, val combinedLeadMs: Long = 40, val gestureOverheadMs: Long = 30)
object PlaybackPlanner {
    fun mappedEventCount(script: PerformanceScript, layout: InstrumentLayout): Int {
        val profile=layout.profile ?: return 0
        if (!ScriptConverter.matchesProfile(script,profile,layout)) return 0
        val targets=layout.targets.associateBy { it.id }
        return script.events.count { e ->
            val target=targets[e.noteKeyId]
            val key=target?.attributes?.get("keyIndex")?.toIntOrNull()
            target?.type == TargetType.NOTE && key != null && profile.pitches[e.pitchRegion]?.getOrNull(key)?.let { it + if (e.semitoneEnabled) 1 else 0 } == e.midiNote
        }
    }
    /** Conservative placeholders, not measured device latency. Explicitly stretch, never silently skip notes. */
    fun plan(script: PerformanceScript, layout: InstrumentLayout, initialRegion: Region?, initialSemitone: Boolean?,
             start: Int, count: Int, speed: Double, controlMs: Long = 150, settleMs: Long = 100, combined: Boolean = false, minimumTouchMs: Long = 1, combinedLeadMs: Long = 40, gestureOverheadMs: Long = 30): PlaybackPlan {
        require(count in 1..100_000 && start >= 0 && start.toLong() + count <= script.events.size && speed in setOf(.5,.75,1.0,2.0))
        require(controlMs in 1..2000 && settleMs in 0..2000)
        require(minimumTouchMs in 1..500)
        require(combinedLeadMs in 0..79)
        require(gestureOverheadMs in 0..500)
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
        if (runCatching { ScriptCodec.encode(script) }.isFailure) errors.add("脚本数据无效")
        // Reject extreme values before subtraction, scaling, or accumulating durations can overflow.
        // The bound reserves ample headroom for all 100,000 control and gesture durations.
        val timeLimit = Long.MAX_VALUE / 4
        val unsafeTime = script.events.any { it.startTimeMs > timeLimit || it.durationMs > timeLimit } ||
            (script.events.last().startTimeMs.toDouble() / speed) > timeLimit.toDouble()
        if (unsafeTime) errors.add("脚本时间超出安全计算范围，请重新转换")
        if (unsafeTime || "脚本数据无效" in errors)
            return PlaybackPlan(emptyList(),0,0,0,warnings.distinct(),errors.distinct(),controlMs,settleMs,combined,minimumTouchMs,0,combinedLeadMs,gestureOverheadMs)
        val targetById = layout.targets.associateBy { it.id }
        val firstInvalid = script.events.indexOfFirst { e ->
            val target = targetById[e.noteKeyId]
            val key = target?.attributes?.get("keyIndex")?.toIntOrNull()
            target?.type != TargetType.NOTE || key == null || profile?.pitches?.get(e.pitchRegion)?.getOrNull(key)?.let { it + if (e.semitoneEnabled) 1 else 0 } != e.midiNote
        }
        if (firstInvalid >= 0) errors.add("脚本第 ${firstInvalid+1} 个事件的目标或音高映射无效")
        val base = script.events[start].startTimeMs
        var currentRegion = initialRegion; var currentSemi = initialSemitone; var cursor=0L; var nr=0; var ns=0
        val original=script.events.subList(start,start+count)
        val keys=layout.targets.filter { it.type == TargetType.NOTE }.mapNotNull { target -> target.attributes["keyIndex"]?.toIntOrNull()?.let { it to target.id } }
            .sortedBy { it.second }.distinctBy { it.first }.toMap()
        val choices=profile?.let { PitchMapper.optimize(it,original.map { event -> event.midiNote },initialRegion,initialSemitone,keys.keys) }
        val selected=original.mapIndexed { index,event ->
            val choice=choices?.get(index)
            if(choice == null) event else event.copy(noteKeyId=keys.getValue(choice.keyIndex),pitchRegion=choice.region,semitoneEnabled=choice.semitone)
        }
        var delayedCount=0; var maxDelay=0L; var extendedNotes=0; var unverifiedShort=0
        val plans=selected.mapIndexed { index,e ->
            val target = targetById[e.noteKeyId]
            val key = target?.attributes?.get("keyIndex")?.toIntOrNull()
            if (target?.type != TargetType.NOTE || key == null || profile?.pitches?.get(e.pitchRegion)?.getOrNull(key)?.let { it + if (e.semitoneEnabled) 1 else 0 } != e.midiNote)
                errors.add("事件 ${start+index+1} 的目标或音高映射无效")
            val changeRegion=currentRegion != e.pitchRegion; val changeSemi=currentSemi != e.semitoneEnabled
            if (changeRegion) { nr++; if (targetById["REGION_${e.pitchRegion.name}"]?.type != TargetType.REGION) errors.add("音区目标缺失：${e.pitchRegion}") }
            if (changeSemi) { ns++; if (targetById["SEMITONE_TOGGLE"]?.type != TargetType.TOGGLE) errors.add("半音目标缺失") }
            if (combined && target != null) {
                val positions=buildList {
                    add(target.position)
                    if (changeRegion) targetById["REGION_${e.pitchRegion.name}"]?.let { add(it.position) }
                    if (changeSemi) targetById["SEMITONE_TOGGLE"]?.let { add(it.position) }
                }
                if (positions.distinct().size != positions.size) errors.add("事件 ${start+index+1} 的组合触摸目标重合，请重新校准")
            }
            val requested=((e.startTimeMs-base)/speed).roundToLong()
            val scaledDuration=(e.durationMs/speed).roundToLong()
            if (scaledDuration < minimumTouchMs) extendedNotes++
            val duration=scaledDuration.coerceAtLeast(minimumTouchMs)
            if (duration < 150) unverifiedShort++
            val maxDuration=MAX_GESTURE_DURATION_MS - if(combined && (changeRegion || changeSemi)) combinedLeadMs else 0
            if (duration !in 1..maxDuration) errors.add("事件 ${start+index+1} 时长 $duration ms 超出无障碍手势范围，最大 $maxDuration ms")
            val before=if(combined) { if(changeRegion || changeSemi) combinedLeadMs else 0L } else (if(changeRegion) controlMs+settleMs+gestureOverheadMs else 0L)+(if(changeSemi) controlMs+settleMs+gestureOverheadMs else 0L)
            val begin=maxOf(requested,cursor+before); val end=begin+duration.coerceAtMost(MAX_GESTURE_DURATION_MS)
            if (begin>requested) { delayedCount++; maxDelay=maxOf(maxDelay,begin-requested) }
            cursor=(if(combined && (changeRegion || changeSemi)) maxOf(end,begin+80-combinedLeadMs) else end)+gestureOverheadMs; currentRegion=e.pitchRegion; currentSemi=e.semitoneEnabled
            PlannedEvent(start+index,requested,begin,end,changeRegion,changeSemi,e)
        }
        if (delayedCount > 0) warnings.add("时间预算不足：$delayedCount 个事件需延迟，最大预计延迟 $maxDelay ms；时间轴已拉伸")
        if (gestureOverheadMs > 0) warnings.add("每次手势额外预留 ${gestureOverheadMs} ms（估算，非设备实测）；休止时间可吸收，实际节奏可能变慢")
        if (extendedNotes > 0) warnings.add("$extendedNotes 个短音按最短触摸 ${minimumTouchMs} ms 延长；后续音符可能推迟")
        if (unverifiedShort > 0) warnings.add("$unverifiedShort 个音符短于此前已验证的 150 ms，真实响应尚未验证")
        return PlaybackPlan(plans,nr,ns,cursor,warnings.distinct(),errors.distinct(),controlMs,settleMs,combined,minimumTouchMs,extendedNotes,combinedLeadMs,gestureOverheadMs)
    }
}
