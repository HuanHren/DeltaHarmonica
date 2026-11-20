package com.huanhren.deltaharmonica

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huanhren.deltaharmonica.input.RootCleanup
import com.huanhren.deltaharmonica.input.RootGestureConnection
import com.huanhren.deltaharmonica.input.RootSequencePlanner
import com.huanhren.deltaharmonica.input.RootSequenceProtocol
import com.huanhren.deltaharmonica.input.RootTouchMain
import com.huanhren.deltaharmonica.input.TapResult
import com.huanhren.deltaharmonica.midi.MonophonyPolicy
import com.huanhren.deltaharmonica.midi.PerformanceScript
import com.huanhren.deltaharmonica.midi.ScriptConverter
import com.huanhren.deltaharmonica.midi.ScriptEvent
import com.huanhren.deltaharmonica.midi.ScriptProvenance
import com.huanhren.deltaharmonica.model.DefaultLayouts
import com.huanhren.deltaharmonica.model.InstrumentLayout
import com.huanhren.deltaharmonica.model.ReferenceDisplay
import com.huanhren.deltaharmonica.model.Region
import com.huanhren.deltaharmonica.model.ScreenPoint
import com.huanhren.deltaharmonica.playback.PlaybackPlan
import com.huanhren.deltaharmonica.playback.PlaybackPlanner
import com.huanhren.deltaharmonica.playback.RootPlaybackMetrics
import com.huanhren.deltaharmonica.playback.RootPlaybackTiming
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Runs the installed isolated APK's production scheduler through the existing shell Root grant. */
@RunWith(AndroidJUnit4::class)
class RootContinuousDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val timing = RootPlaybackTiming()

    @Test fun eightAndSixteenNotesReachTheIsolatedWindowInOrder() = withReceiver { receiver, layout, points, touch ->
        for ((count, speed) in listOf(8 to 1.0, 8 to 2.0, 16 to 1.0, 16 to 2.0)) {
            val states = listOf(
                Region.HIGH to true, Region.HIGH to true, Region.LOW to false, Region.LOW to false,
                Region.NATURAL to true, Region.NATURAL to false, Region.HIGH to false, Region.LOW to true,
            )
            val keys = listOf(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8)
            val events = List(count) { i ->
                val (region, semitone) = states[i % states.size]
                val sameKeyPair = i % 2 != 0
                val start = (i / 2) * 660L + if (sameKeyPair) 120L else 0L
                val hold = if (sameKeyPair) 130L else 75L
                event(layout, start, hold, keys[i], region, semitone)
            }
            verifyScore(receiver, layout, points, touch, events, speed)
        }
    }

    @Test fun threeHundredSixtyNotesReachTheIsolatedWindowAtBothSpeeds() = withReceiver { receiver, layout, points, touch ->
        val states = listOf(Region.NATURAL to false, Region.HIGH to true, Region.LOW to false, Region.NATURAL to true)
        val keys = listOf(1, 1, 2, 3, 3, 4, 5, 5, 6, 7, 7, 8)
        val intervals = listOf(216L, 182L, 200L, 224L, 190L, 220L)
        val holds = listOf(55L, 125L, 95L, 75L, 135L, 100L)
        var onset = 0L
        val events = List(360) { index ->
            val (region, semitone) = states[(index / 30) % states.size]
            val note = event(layout, onset, holds[index % holds.size], keys[index % keys.size], region, semitone)
            // Reserve room at phrase boundaries for two settled control touches, including at 2×.
            onset += if ((index + 1) % 30 == 0) 620L else intervals[index % intervals.size]
            note
        }
        val firstSpan = verifyScore(receiver, layout, points, touch, events, 1.0)
        val secondSpan = verifyScore(receiver, layout, points, touch, events, 2.0)
        assertTrue("Combined Receiver spans outside the soak budget: $firstSpan + $secondSpan ms",
            firstSpan + secondSpan in 110_000L..125_000L)
    }

    @Test fun pausesAtReleaseResumesAndStopsTheActiveNote() = withReceiver { receiver, layout, points, touch ->
        val events = listOf(
            event(layout, 0L, 420L, 1, Region.NATURAL, false),
            event(layout, 700L, 1_000L, 2, Region.NATURAL, false),
            event(layout, 2_000L, 120L, 3, Region.NATURAL, false),
        )
        val sequence = RootSequencePlanner.build(plan(layout, events, 1.0), points)
        val pause = AtomicBoolean()
        val stop = AtomicBoolean()
        val paused = CompletableDeferred<Unit>()
        val resumed = CompletableDeferred<Unit>()
        withTimeout(9_000) {
            val playing = async {
                touch.playSequence(sequence, { stop.get() || !receiver.focused }, pause::get, { _, _, _ -> },
                    { paused.complete(Unit) }, { resumed.complete(Unit) }, beforeResume = { receiver.focused })
            }
            receiver.awaitCount(1)
            pause.set(true)
            paused.await()
            val first = receiver.awaitCount(2)
            assertContacts(first, listOf(points.getValue("NOTE_1")))
            assertTrue("Pause shortened the active hold", first[1].time - first[0].time >= 420)
            delay(250)
            assertEquals(first, receiver.snapshot())
            pause.set(false)
            resumed.await()
            val secondDown = receiver.awaitCount(3).last()
            assertEquals(MotionEvent.ACTION_DOWN, secondDown.action)
            assertEquals(points.getValue("NOTE_2"), secondDown.point)
            stop.set(true)
            val result = playing.await()
            assertEquals(TapResult.Cancelled, result.result)
            assertEquals(1, result.completed)
            assertContacts(receiver.awaitCount(4), listOf(points.getValue("NOTE_1"), points.getValue("NOTE_2")))
            delay(1_500)
            assertEquals(4, receiver.snapshot().size)
        }
    }

    private fun expectedTouches(events: List<ScriptEvent>): List<String> = buildList {
        var region = Region.NATURAL
        var semitone = false
        events.forEach { event ->
            if (region != event.pitchRegion) {
                region = event.pitchRegion
                add("REGION_${region.name}")
            }
            if (semitone != event.semitoneEnabled) {
                semitone = event.semitoneEnabled
                add("SEMITONE_TOGGLE")
            }
            add(event.noteKeyId)
        }
    }

    private suspend fun verifyScore(
        receiver: RecordingView,
        layout: InstrumentLayout,
        points: Map<String, ScreenPoint>,
        touch: RootGestureConnection,
        events: List<ScriptEvent>,
        speed: Double,
    ): Long {
        val expected = expectedTouches(events)
        val plan = plan(layout, events, speed)
        val completed = mutableListOf<Int>()
        val noteTimes = mutableListOf<Pair<Long, Long>>()
        val metrics = RootPlaybackMetrics(timing.maxDownLatenessMs)
        val sequence = RootSequencePlanner.build(plan, points)
        val result = withTimeout(maxOf(15_000L, plan.estimatedTotalMs + 5_000L)) {
            touch.playSequence(sequence, { !receiver.focused }, { false },
                { index, _, _ -> completed.add(index) }, {}, {}, onTiming = { index, planned, down, up ->
                    val item = plan.events[index]
                    noteTimes.add(down to up)
                    metrics.record(planned, down, up, item.predictedEndMs - item.predictedStartMs,
                        item.predictedStartMs - item.requestedStartMs)
                })
        }
        assertEquals(TapResult.Completed, result.result)
        assertEquals(events.size, result.completed)
        assertEquals(events.indices.toList(), completed)
        val signals = receiver.awaitCount(expected.size * 2)
        assertContacts(signals, expected.map(points::getValue))
        val noteContacts = expected.indices.filter { expected[it].startsWith("NOTE_") }
        assertEquals(noteContacts.map { signals[it * 2].time to signals[it * 2 + 1].time }, noteTimes)
        for (i in 1 until noteContacts.size) {
            val previous = noteContacts[i - 1]
            val next = noteContacts[i]
            val gap = signals[next * 2].time - signals[previous * 2 + 1].time
            val required = if (expected[previous] == expected[next]) timing.sameKeyGapMs else timing.differentKeyGapMs
            assertTrue("Note release gap ${expected[previous]} -> ${expected[next]}: $gap < $required", gap >= required)
        }
        expected.indices.forEach { index ->
            val duration = signals[index * 2 + 1].time - signals[index * 2].time
            val minimum = if (expected[index].startsWith("NOTE_")) timing.minNoteHoldMs else timing.controlHoldMs
            assertTrue("Hold for ${expected[index]}: $duration < $minimum", duration >= minimum)
            if (index > 0 && !expected[index - 1].startsWith("NOTE_")) {
                val settle = signals[index * 2].time - signals[index * 2 - 1].time
                assertTrue("Settle after ${expected[index - 1]}: $settle < ${timing.controlSettleMs}",
                    settle >= timing.controlSettleMs)
            }
        }
        val span = signals.last().time - signals.first().time
        Log.i("DeltaRootTest", "notes=${events.size} speed=$speed callbacks=${signals.size} spanMs=$span ${metrics.summary()}")
        receiver.clear()
        return span
    }

    private fun assertContacts(actual: List<Signal>, expected: List<ScreenPoint>) {
        assertEquals("Touch callback count", expected.size * 2, actual.size)
        expected.forEachIndexed { index, point ->
            val down = actual[index * 2]
            val up = actual[index * 2 + 1]
            assertEquals(MotionEvent.ACTION_DOWN, down.action)
            assertEquals(MotionEvent.ACTION_UP, up.action)
            assertEquals(1, down.pointerCount)
            assertEquals(1, up.pointerCount)
            assertEquals(down.pointerId, up.pointerId)
            assertEquals(point, down.point)
            assertEquals(point, up.point)
            assertEquals(down.time, down.downTime)
            assertEquals(down.downTime, up.downTime)
            assertTrue("UP precedes DOWN at $point", up.time >= down.time)
        }
    }

    private fun event(layout: InstrumentLayout, at: Long, hold: Long, key: Int, region: Region, semitone: Boolean): ScriptEvent {
        val pitch = requireNotNull(layout.profile).pitches.getValue(region)[key - 1] + if (semitone) 1 else 0
        return ScriptEvent(at, hold, pitch, "NOTE_$key", region, semitone)
    }

    private fun plan(layout: InstrumentLayout, events: List<ScriptEvent>, speed: Double): PlaybackPlan {
        val profile = requireNotNull(layout.profile)
        val script = PerformanceScript(
            schemaVersion = 2, instrumentProfileId = profile.id,
            profileFingerprint = ScriptConverter.fingerprint(profile, layout),
            sourceMidiName = "Isolated Root test", sourceTrack = 0, monophonyPolicy = MonophonyPolicy.HIGHEST_NOTE,
            mappingVerified = profile.verified, events = events, provenance = ScriptProvenance("CLEAN", emptyList(), 0, 0),
        )
        return PlaybackPlanner.plan(script, layout, Region.NATURAL, false, 0, events.size, speed, rootTiming = timing)
            .also { check(it.fatalErrors.isEmpty()) { it.fatalErrors.toString() } }
    }

    private fun withReceiver(
        test: suspend (RecordingView, InstrumentLayout, Map<String, ScreenPoint>, RootGestureConnection) -> Unit,
    ) {
        requireIsolatedTestHost()
        check(InstrumentationRegistry.getArguments().getString("rootContinuous") == "true") {
            "Run Root input tests with the guarded runner's -RootContinuous option"
        }
        val app = instrumentation.targetContext.applicationContext as IsolatedTestApplication
        check(!app.rootUiAllowed)
        val uid = android.os.Process.myUid()
        val info = app.applicationInfo
        check(info.uid == uid && uid in 10_000..19_999)
        val apk = info.sourceDir
        check(info.splitSourceDirs.isNullOrEmpty() && isolatedApk.matches(apk)) { "Unexpected isolated base APK route" }

        withIsolatedActivity {
            val receiver = AtomicReference<RecordingView>()
            instrumentation.runOnMainSync {
                val activity = resumedIsolatedActivity()
                val view = RecordingView(activity)
                view.keepScreenOn = true
                activity.addContentView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                receiver.set(view)
            }
            val view = receiver.get()
            runBlocking {
                val fixture = withTimeout(5_000) {
                    var ready: Pair<InstrumentLayout, Map<String, ScreenPoint>>? = null
                    val geometry = AtomicReference<Pair<InstrumentLayout, Map<String, ScreenPoint>>?>()
                    while (ready == null) {
                        instrumentation.runOnMainSync {
                            view.focused = view.hasWindowFocus() && view.isAttachedToWindow &&
                                view.display?.displayId == Display.DEFAULT_DISPLAY
                            if (view.focused && view.width > 100 && view.height > 100) {
                                val origin = IntArray(2).also(view::getLocationOnScreen)
                                val layout = receiverLayout(view.width, view.height)
                                val points = layout.targets.mapIndexed { index, target ->
                                    val x = origin[0] + view.width * ((index % 4) + 1) / 5
                                    val y = origin[1] + view.height * ((index / 4) + 1) / 4
                                    target.id to ScreenPoint(x.toFloat(), y.toFloat())
                                }.toMap()
                                geometry.set(layout to points)
                            }
                        }
                        ready = geometry.get()
                        if (ready == null) delay(25)
                    }
                    ready
                }
                check(view.focused)
                // The shell grant drives a helper limited to the isolated UID. The host keeps its disabled Root runner.
                val command = "exec su -c 'CLASSPATH=$apk exec /system/bin/app_process /system/bin ${RootTouchMain::class.java.name} $uid' 2>&1"
                // UiAutomation tokenizes its command using Runtime.exec. Send the quoted launch
                // line to the shell first, then use the same pipe for the helper's protocol.
                ShellProcess(instrumentation.uiAutomation.executeShellCommandRw("/system/bin/sh -es")).use { process ->
                    process.outputStream.write("$command\n".toByteArray(Charsets.US_ASCII))
                    process.outputStream.flush()
                    process.expectReady()
                    val touch = RootGestureConnection(process, null)
                    var failure: Throwable? = null
                    try {
                        check(view.focused)
                        test(view, fixture.first, fixture.second, touch)
                    } catch (problem: Throwable) {
                        failure = problem
                        throw problem
                    } finally {
                        try {
                            assertEquals("Helper cleanup acknowledgement", RootCleanup.ACKNOWLEDGED, touch.close())
                        } catch (cleanupFailure: Throwable) {
                            val original = failure
                            if (original == null) throw cleanupFailure
                            original.addSuppressed(cleanupFailure)
                        }
                    }
                }
            }
        }
    }

    private fun receiverLayout(width: Int, height: Int): InstrumentLayout {
        val layout = DefaultLayouts.create()
        val starts = mapOf(Region.LOW to 36, Region.NATURAL to 54, Region.HIGH to 72)
        // Each key has two unique pitches so reoptimizing fingerings cannot swap the test's controls.
        val pitches = starts.mapValues { (_, base) -> List(8) { base + 2 * it } }
        // Model a landscape calibration even when the isolated receiver opens in portrait.
        // The injected points are calculated separately in the receiver's real screen coordinates.
        return layout.copy(referenceDisplay = ReferenceDisplay(maxOf(width, height), minOf(width, height)),
            profile = requireNotNull(layout.profile).copy(pitches = pitches))
    }

    private data class Signal(
        val action: Int,
        val point: ScreenPoint,
        val time: Long,
        val downTime: Long,
        val pointerId: Int,
        val pointerCount: Int,
    )

    private class RecordingView(activity: MainActivity) : View(activity) {
        @Volatile var focused = false
        private val signals = mutableListOf<Signal>()

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            focused = hasWindowFocus
        }

        override fun onDetachedFromWindow() {
            focused = false
            super.onDetachedFromWindow()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val action = event.actionMasked
            if (action != MotionEvent.ACTION_MOVE) synchronized(signals) {
                signals.add(Signal(action, ScreenPoint(event.rawX.roundToInt().toFloat(), event.rawY.roundToInt().toFloat()),
                    event.eventTime, event.downTime, event.getPointerId(event.actionIndex), event.pointerCount))
            }
            return true
        }

        fun snapshot() = synchronized(signals) { signals.toList() }
        fun clear() = synchronized(signals) { signals.clear() }

        suspend fun awaitCount(count: Int): List<Signal> = withTimeout(4_000) {
            var received = snapshot()
            while (received.size < count) {
                delay(10)
                received = snapshot()
            }
            received
        }
    }

    /** UiAutomation pipes implement the parts of Process consumed by RootGestureConnection. */
    private class ShellProcess(descriptors: Array<ParcelFileDescriptor>) : Process(), Closeable {
        private val outputDescriptor = descriptors[0]
        private val output = ParcelFileDescriptor.AutoCloseInputStream(outputDescriptor)
        private val input = ParcelFileDescriptor.AutoCloseOutputStream(descriptors[1])

        override fun getInputStream() = output
        override fun getOutputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = error("UiAutomation exposes shell pipes, no wait handle")
        override fun exitValue(): Int = throw IllegalThreadStateException("UiAutomation exposes no exit code")
        override fun destroy() = close()

        fun expectReady() {
            val deadline = SystemClock.uptimeMillis() + 4_000
            val poll = StructPollfd().apply {
                fd = outputDescriptor.fileDescriptor
                events = OsConstants.POLLIN.toShort()
            }
            val line = StringBuilder()
            var lastLine = ""
            while (SystemClock.uptimeMillis() < deadline) {
                val remaining = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0).toInt()
                if (Os.poll(arrayOf(poll), remaining) == 0) break
                val next = output.read()
                if (next == -1) break
                if (next == 10) {
                    lastLine = line.toString().trimEnd()
                    if (lastLine == "DELTA READY ${RootSequenceProtocol.VERSION}") return
                    line.setLength(0)
                } else if (line.length < 300) {
                    line.append(next.toChar())
                }
            }
            error("Isolated shell Root helper did not become ready: $lastLine$line")
        }

        override fun close() {
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    companion object {
        private val isolatedApk = Regex(
            """^/data/app/[~A-Za-z0-9_=.-]+/com\.huanhren\.deltaharmonica\.instrumented-[A-Za-z0-9_=.-]+/base\.apk$""",
        )
    }
}
