package com.huanhren.deltaharmonica

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.huanhren.deltaharmonica.input.FocusedWindow
import com.huanhren.deltaharmonica.input.RootGestureConnection
import com.huanhren.deltaharmonica.input.TapResult
import com.huanhren.deltaharmonica.input.displaySnapshot
import com.huanhren.deltaharmonica.midi.MonophonyPolicy
import com.huanhren.deltaharmonica.midi.MidiParser
import com.huanhren.deltaharmonica.midi.PerformanceScript
import com.huanhren.deltaharmonica.midi.ScriptConverter
import com.huanhren.deltaharmonica.midi.ScriptEvent
import com.huanhren.deltaharmonica.midi.ScriptProvenance
import com.huanhren.deltaharmonica.model.Coordinates
import com.huanhren.deltaharmonica.model.DefaultLayouts
import com.huanhren.deltaharmonica.model.InstrumentLayout
import com.huanhren.deltaharmonica.model.ReferenceDisplay
import com.huanhren.deltaharmonica.model.Region
import com.huanhren.deltaharmonica.model.ScreenPoint
import com.huanhren.deltaharmonica.model.ScreenSize
import com.huanhren.deltaharmonica.model.TargetType
import com.huanhren.deltaharmonica.playback.CombinedGesture
import com.huanhren.deltaharmonica.playback.PlaybackPlanner
import com.huanhren.deltaharmonica.playback.PlaybackPreparation
import com.huanhren.deltaharmonica.playback.PlaybackRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToLong

/** Debug-only receiving window for ADB-driven physical-device regressions. */
class RootInputDiagnosticActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var receiver: Receiver
    private val fixture = DefaultLayouts.create(1)
    private val root get() = (application as DeltaHarmonicaApp).rootBackend

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        receiver = Receiver()
        setContentView(receiver)
        scope.launch {
            try {
                kotlinx.coroutines.withTimeout(5_000) {
                    while (!hasWindowFocus() || receiver.width == 0) delay(50)
                }
                val access = root.checkAccess()
                Log.i(TAG, "ACCESS ${access.availability} ${access.message}")
                check(access.available) { access.message }
                kotlinx.coroutines.withTimeout(5_000) {
                    while (!hasWindowFocus() || receiver.width == 0) delay(50)
                }
                check(hasWindowFocus())
                val testCase = intent.getStringExtra("case") ?: "suite"
                when (testCase) {
                    "stop" -> verifyStop()
                    "reject" -> verifyRejectedDispatch()
                    "game" -> probeGame()
                    else -> verifySuite()
                }
                if (testCase != "game") Log.i(TAG, "PASS case=$testCase")
            } catch (e: Exception) {
                Log.e(TAG, "FAIL ${e.message}", e)
            }
        }
    }

    private suspend fun withTouch(uid: Int = Process.myUid(), onVolumeDown: (() -> Unit)? = null, block: suspend (RootGestureConnection) -> Unit) {
        val touch = checkNotNull(root.openTouch(uid, onVolumeDown)) { "helper unavailable" }
        try { block(touch) } finally { root.closeTouch(touch) }
        check(!root.releaseUncertain) { "cleanup did not complete" }
    }

    private suspend fun verifySuite() = withTouch { touch ->
        for (key in 1..8) {
            val id = "NOTE_$key"
            check(touch.play(CombinedGesture.create(receiver.point(id), 35, emptyList())) == TapResult.Completed)
        }
        receiver.awaitIdle(8)
        check(receiver.hits.map { it.id } == (1..8).map { "NOTE_$it" })
        check(receiver.hits.all { abs(it.duration - 35L) <= 25 })
        Log.i(TAG, "KEYS 8/8 actualMs=${receiver.hits.map { it.duration }}")
        for (speed in listOf(1.0, 2.0)) {
            for (count in listOf(8, 16, 24)) verifyScore(touch, count, speed, 550)
        }
        if (!intent.getBooleanExtra("quick", false)) verifyScore(touch, 24, 1.0, 2_600)
    }

    private suspend fun verifyScore(touch: RootGestureConnection, count: Int, speed: Double, frequency: Long) {
        val layout = fixture.copy(referenceDisplay = ReferenceDisplay(receiver.width, receiver.height))
        val profile = checkNotNull(layout.profile)
        val durations = listOf(30L, 150L, 400L, 75L, 250L, 500L)
        val song = PerformanceScript(
            schemaVersion = 2, instrumentProfileId = profile.id,
            profileFingerprint = ScriptConverter.fingerprint(profile, layout), sourceMidiName = "Root input diagnostic",
            sourceTrack = 0, monophonyPolicy = MonophonyPolicy.HIGHEST_NOTE, mappingVerified = false,
            events = (0 until 24).map {
                val region = listOf(Region.HIGH, Region.NATURAL, Region.LOW)[(it / 4) % 3]
                val semitone = it % 2 != 0
                val index = it % 8
                ScriptEvent(it * frequency, durations[it % durations.size], profile.pitches.getValue(region)[index] + if (semitone) 1 else 0,
                    "NOTE_${index + 1}", region, semitone)
            },
            provenance = ScriptProvenance("CLEAN", emptyList(), 0, 0),
        )
        val plan = PlaybackPlanner.plan(song, layout, Region.NATURAL, false, 0, count, speed, combined = true)
        check(plan.fatalErrors.isEmpty()) { plan.fatalErrors.toString() }
        receiver.hits.clear()
        var progress = 0
        var restored = 0
        var epoch = 0L
        val result = PlaybackRunner().run(
            plan, {}, {}, { hasWindowFocus() }, { _, _ -> error("unexpected sequential dispatch") }, { restored++ },
            dryRunCompleted = true, warningsConfirmed = true, onSessionStarted = { epoch = it },
            onEvent = { completed, _, finished -> if (finished) progress = completed },
            dispatchCombined = { item, duration ->
                val controls = buildList {
                    if (item.regionSwitch) add(receiver.point("REGION_${item.event.pitchRegion.name}"))
                    if (item.semitoneSwitch) add(receiver.point("SEMITONE_TOGGLE"))
                }
                touch.play(CombinedGesture.create(receiver.point(item.event.noteKeyId), duration, controls))
            },
        )
        receiver.awaitIdle(count + plan.regionSwitches + plan.semitoneSwitches)
        check(result.failure == null && progress == count && restored == 1) { result.toString() }
        val notes = receiver.hits.filter { it.id.startsWith("NOTE_") }
        check(notes.map { it.id } == plan.events.map { it.event.noteKeyId })
        val expectedControls = plan.events.flatMap { item ->
            buildList {
                if (item.regionSwitch) add("REGION_${item.event.pitchRegion.name}")
                if (item.semitoneSwitch) add("SEMITONE_TOGGLE")
            }
        }
        check(receiver.hits.filterNot { it.id.startsWith("NOTE_") }.map { it.id } == expectedControls)
        check(notes.map { it.withControl } == plan.events.map { it.regionSwitch || it.semitoneSwitch })
        check(receiver.hits.none { it.cancelled } && receiver.active.isEmpty() && !receiver.overlap)
        val durationErrors = notes.zip(plan.events).map { (hit, item) ->
            abs(hit.duration - (item.event.durationMs / speed).roundToLong())
        }
        check(durationErrors.all { it <= 25 }) { "duration errors=$durationErrors" }
        val startErrors = notes.zip(plan.events).map { (hit, item) -> hit.down - epoch - item.predictedStartMs }
        Log.i(TAG, "SCORE count=$count speed=$speed spacing=$frequency durationMax=${durationErrors.max()} " +
            "startMax=${startErrors.max()} region=${plan.regionSwitches} semi=${plan.semitoneSwitches} " +
            "combined=${notes.count { it.withControl }} elapsed=${result.elapsedMs} lastReleased=${receiver.active.isEmpty()}")
    }

    private suspend fun verifyStop() {
        val layout = fixture.copy(referenceDisplay = ReferenceDisplay(receiver.width, receiver.height))
        val profile = checkNotNull(layout.profile)
        val events = (0..1).map { ScriptEvent(it * 58_000L, 57_000, 60, "NOTE_1", Region.NATURAL, false) }
        val song = PerformanceScript(
            schemaVersion = 2, instrumentProfileId = profile.id, profileFingerprint = ScriptConverter.fingerprint(profile, layout),
            sourceMidiName = "Stop", sourceTrack = 0, monophonyPolicy = MonophonyPolicy.HIGHEST_NOTE, mappingVerified = false,
            events = events, provenance = ScriptProvenance("CLEAN", emptyList(), 0, 0),
        )
        val plan = PlaybackPlanner.plan(song, layout, Region.NATURAL, false, 0, events.size, 1.0)
        val runner = PlaybackRunner()
        val priorProgress = PlaybackPreparation.progress.value
        val owner = checkNotNull(PlaybackPreparation.beginSession {})
        if (!PlaybackPreparation.attachRunner(owner, runner)) {
            PlaybackPreparation.finishSession(owner, priorProgress)
            error("Playback session already stopping")
        }
        val automatic = intent.getBooleanExtra("automatic", false)
        val stopJob = if (automatic) scope.launch {
            kotlinx.coroutines.withTimeout(5_000) {
                while (receiver.active.isEmpty()) delay(10)
            }
            delay(500)
            PlaybackPreparation.stopPlayback(owner)
        } else null
        try {
            withTouch(onVolumeDown = { PlaybackPreparation.stopPlayback(owner) }) { touch ->
                val result = runner.run(
                    plan, {}, {}, { hasWindowFocus() }, { id, duration ->
                        touch.play(CombinedGesture.create(receiver.point(id), duration, emptyList()), stop = { runner.stopRequested })
                    }, {}, dryRunCompleted = true, warningsConfirmed = true,
                )
                check(result.stopped && result.failure == null && result.remaining > 0) { result.toString() }
                receiver.awaitIdle(result.completed + 1)
                val released = receiver.hits.last()
                check(receiver.hits.size == result.completed + 1)
                check(released.id == "NOTE_1" && !released.cancelled)
                Log.i(TAG, "STOP completed=${result.completed} pending=${result.remaining} releasedAt=${released.down + released.duration} heldMs=${released.duration}")
            }
            withTouch { touch ->
                val expected = receiver.hits.size + 1
                check(touch.play(CombinedGesture.create(receiver.point("NOTE_2"), 45, emptyList())) == TapResult.Completed)
                receiver.awaitIdle(expected)
                check(receiver.hits.last().id == "NOTE_2")
            }
            Log.i(TAG, "RECOVERY NOTE_2 received")
        } finally {
            stopJob?.cancel()
            PlaybackPreparation.finishSession(owner, priorProgress)
        }
    }

    private suspend fun verifyRejectedDispatch() {
        val gameUid = packageManager.getApplicationInfo(FocusedWindow.GAME_PACKAGE, 0).uid
        withTouch(gameUid) { touch ->
            val result = touch.play(CombinedGesture.create(receiver.point("NOTE_1"), 45, emptyList()))
            check(result != TapResult.Completed && receiver.hits.isEmpty()) { "foreign UID accepted input" }
        }
        withTouch { touch ->
            check(touch.play(CombinedGesture.create(receiver.point("NOTE_2"), 45, emptyList())) == TapResult.Completed)
            receiver.awaitIdle(1)
            check(receiver.hits.single().id == "NOTE_2")
        }
    }

    /** Coordinates come from the current game screen and remain in memory. */
    private suspend fun probeGame() {
        val before = displaySnapshot(this)
        check(before.supported)
        val positions = requireNotNull(intent.getStringExtra("points")).split(':').map { token ->
            val values = token.split(',')
            require(values.size == 2)
            ScreenPoint(values[0].toFloat(), values[1].toFloat())
        }
        val targets = fixture.targets
        require(positions.size == targets.size && positions.distinct().size == targets.size && positions.all(root::validPoint))
        val points = targets.map { it.id }.zip(positions).toMap()
        val gameUid = packageManager.getApplicationInfo(FocusedWindow.GAME_PACKAGE, 0).uid
        check(moveTaskToBack(true))
        kotlinx.coroutines.withTimeout(8_000) {
            while (root.focusedPackage() != FocusedWindow.GAME_PACKAGE) delay(100)
        }
        var stopped = false
        withTouch(gameUid, onVolumeDown = { stopped = true }) { touch ->
            val midi = intent.getStringExtra("midi")
            if (midi != null) {
                val layout = fixture.copy(
                    referenceDisplay = ReferenceDisplay(before.size.width, before.size.height),
                    targets = targets.zip(positions).map { (target, point) ->
                        target.copy(position = Coordinates.fromLocal(point, ScreenPoint(0f, 0f), before.size))
                    },
                )
                playGameMidi(midi, layout, touch, { stopped }) { displaySnapshot(this) == before }
                return@withTouch
            }
            suspend fun gesture(id: String, duration: Long, controls: List<String> = emptyList()) {
                check(!stopped && displaySnapshot(this) == before && root.focusedPackage() == FocusedWindow.GAME_PACKAGE)
                val result = touch.play(
                    CombinedGesture.create(points.getValue(id), duration, controls.map(points::getValue)),
                    stop = { stopped || displaySnapshot(this) != before },
                ) { timing ->
                    Log.i(TAG, "GAME_DISPATCH id=$id controls=$controls duration=$duration down=${timing.noteDown} up=${timing.noteUp} status=${timing.status}")
                }
                check(result == TapResult.Completed) { "game dispatch failed at $id: $result" }
                delay(220)
            }
            for (key in 1..8) gesture("NOTE_$key", if (key == 8) 1_100 else 300)
            for (region in listOf("HIGH", "LOW", "NATURAL")) {
                gesture("REGION_$region", 150)
                gesture("NOTE_3", 500)
            }
            gesture("NOTE_3", 900, listOf("REGION_HIGH", "SEMITONE_TOGGLE"))
            gesture("SEMITONE_TOGGLE", 150)
            gesture("REGION_NATURAL", 150)
        }
        Log.i(TAG, "GAME_DISPATCH_COMPLETE receiver=external")
    }

    private suspend fun playGameMidi(
        name: String,
        layout: InstrumentLayout,
        touch: RootGestureConnection,
        stopped: () -> Boolean,
        displayValid: () -> Boolean,
    ) = coroutineScope {
        require(name in setOf("duration16-test.mid", "pitch-switch-test.mid", "stage6-60s.mid", "stage6-180s.mid"))
        val file = cacheDir.resolve(name)
        require(file.length() in 1L..8_192L)
        val midi = MidiParser.parse(file.readBytes())
        val script = ScriptConverter.convert(midi, name, 0, MonophonyPolicy.HIGHEST_NOTE, 0, requireNotNull(layout.profile), layout).script
        val count = intent.getIntExtra("count", script.events.size)
        val speed = intent.getIntExtra("speed", 1).toDouble()
        val initialRegion = Region.valueOf(intent.getStringExtra("region") ?: "NATURAL")
        val initialSemitone = intent.getBooleanExtra("semitone", false)
        val plan = PlaybackPlanner.plan(script, layout, initialRegion, initialSemitone, 0, count, speed, combined = true)
        check(plan.fatalErrors.isEmpty()) { plan.fatalErrors.toString() }
        val display = requireNotNull(layout.referenceDisplay)
        val points = layout.targets.associate { it.id to Coordinates.toScreen(it.position, ScreenSize(display.width, display.height)) }
        val runner = PlaybackRunner()
        var focused: String? = FocusedWindow.GAME_PACKAGE
        var focusTime = SystemClock.elapsedRealtime()
        val watchFocus = launch {
            while (isActive) {
                focused = root.focusedPackage()
                focusTime = SystemClock.elapsedRealtime()
                if (stopped()) runner.requestStop()
                delay(100)
            }
        }
        fun valid() = displayValid() && FocusedWindow.isFreshGame(focused, focusTime, SystemClock.elapsedRealtime())
        val result = try { runner.run(
            plan, {}, {}, ::valid, { _, _ -> error("unexpected sequential dispatch") }, {},
            dryRunCompleted = true, warningsConfirmed = true,
            dispatchCombined = { item, duration ->
                if (stopped()) runner.requestStop()
                val controls = buildList {
                    if (item.regionSwitch) add(points.getValue("REGION_${item.event.pitchRegion.name}"))
                    if (item.semitoneSwitch) add(points.getValue("SEMITONE_TOGGLE"))
                }
                touch.play(CombinedGesture.create(points.getValue(item.event.noteKeyId), duration, controls),
                    stop = { stopped() || !valid() },
                ) { timing ->
                    Log.i(TAG, "GAME_MIDI index=${item.sourceIndex} note=${item.event.noteKeyId} duration=$duration status=${timing.status} down=${timing.noteDown} up=${timing.noteUp}")
                    if (stopped()) runner.requestStop()
                }
            },
        ) } finally { watchFocus.cancel() }
        check(result.failure == null) { result.toString() }
        Log.i(TAG, "GAME_MIDI_DISPATCH name=$name speed=$speed completed=${result.completed} remaining=${result.remaining} stopped=${result.stopped} elapsed=${result.elapsedMs}")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private data class Active(val id: String, val down: Long, val withControl: Boolean)
    private data class Hit(val id: String, val down: Long, val duration: Long, val withControl: Boolean, val cancelled: Boolean)

    private inner class Receiver : View(this@RootInputDiagnosticActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f }
        val hits = mutableListOf<Hit>()
        val active = mutableMapOf<Int, Active>()
        var overlap = false

        fun point(id: String) = Coordinates.toScreen(fixture.targets.first { it.id == id }.position, ScreenSize(width, height))

        suspend fun awaitIdle(count: Int) = kotlinx.coroutines.withTimeout(750) {
            while (hits.size < count || active.isNotEmpty()) delay(10)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(19, 29, 37))
            canvas.drawText("DeltaHarmonica Root input verification", width * .1f, height * .2f, paint)
            fixture.targets.forEach { target ->
                val p = point(target.id)
                canvas.drawText(target.id, p.x - 38f, p.y - 35f, paint)
                canvas.drawCircle(p.x, p.y, if (active.values.any { it.id == target.id }) 27f else 10f, paint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val index = event.actionIndex
            val pointer = event.getPointerId(index)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val hit = fixture.targets.minBy { target ->
                        val p = point(target.id)
                        abs(p.x - event.getX(index)) + abs(p.y - event.getY(index))
                    }
                    if (hit.type == TargetType.NOTE && active.values.any { it.id.startsWith("NOTE_") }) overlap = true
                    active[pointer] = Active(hit.id, event.eventTime, active.values.any { !it.id.startsWith("NOTE_") })
                    Log.i(TAG, "DOWN id=${hit.id} t=${event.eventTime} pointers=${event.pointerCount}")
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                    active.remove(pointer)?.let { record(it, event.eventTime, false) }
                MotionEvent.ACTION_CANCEL -> {
                    active.values.forEach { record(it, event.eventTime, true) }
                    active.clear()
                }
            }
            invalidate()
            return true
        }

        private fun record(active: Active, now: Long, cancelled: Boolean) {
            val hit = Hit(active.id, active.down, now - active.down, active.withControl, cancelled)
            hits.add(hit)
            Log.i(TAG, "RELEASE id=${hit.id} t=$now duration=${hit.duration} cancel=$cancelled")
        }
    }

    private companion object { const val TAG = "DeltaRootReceiver" }
}
