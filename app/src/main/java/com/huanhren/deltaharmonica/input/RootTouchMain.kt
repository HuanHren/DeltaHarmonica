package com.huanhren.deltaharmonica.input

import android.os.SystemClock
import android.system.Os
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/** Privileged process entry point. Reads a fixed gesture protocol from the app's private pipe. */
object RootTouchMain {
    @JvmStatic
    fun main(args: Array<String>) {
        check(Os.getuid() == 0)
        val targetUid = args[0].toInt()
        val watchVolume = args.getOrNull(1) == "volume"
        require(targetUid >= 0)
        val managerClass = Class.forName("android.hardware.input.InputManagerGlobal")
        val manager = managerClass.getMethod("getInstance").invoke(null)
        val inject = managerClass.getMethod(
            "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        )
        val worker = java.util.concurrent.atomic.AtomicReference<Thread>()
        val stopped = AtomicBoolean()
        val busy = AtomicBoolean()
        val safe = AtomicBoolean(true)
        val volumeStopped = AtomicBoolean()
        fun reply(line: String) = System.out.println("DELTA $line")
        val volume = if (watchVolume) RootVolumeKeyMonitor.start {
            if (volumeStopped.compareAndSet(false, true)) {
                stopped.set(true)
                worker.get()?.let(LockSupport::unpark)
                reply("VOLUME ${SystemClock.uptimeMillis()}")
            }
        } else null
        if (watchVolume) reply(if (volume == null) "NO_VOLUME_MONITOR" else "VOLUME_READY")
        reply("READY 0")
        try {
            System.`in`.bufferedReader().forEachLine { line ->
                when {
                    line == "STOP" -> {
                        stopped.set(true)
                        worker.get()?.let(LockSupport::unpark)
                    }
                    line.startsWith("PLAY ") && volumeStopped.get() -> reply("DONE STOPPED -1 -1")
                    line.startsWith("PLAY ") && !busy.get() && safe.get() -> {
                        val strokes = parse(line.removePrefix("PLAY "))
                        if (strokes == null) {
                            reply("DONE FAILED -1 -1")
                        } else {
                            stopped.set(false)
                            busy.set(true)
                            val playback = Thread {
                                val outcome = RootTouchTimeline.play(strokes, SystemClock::uptimeMillis, waitUntil = { deadline ->
                                    while (true) {
                                        if (stopped.get() || volumeStopped.get()) throw InterruptedException()
                                        val remaining = deadline - SystemClock.uptimeMillis()
                                        if (remaining <= 0) break
                                        LockSupport.parkNanos(remaining * 1_000_000)
                                    }
                                }) { kind, id, pointers, down, time ->
                                    val event = motion(kind, id, pointers, down, time)
                                    try {
                                        // WAIT_FOR_RESULT. Target only the selected Android UID.
                                        try { inject.invoke(manager, event, 1, targetUid) == true }
                                        catch (e: InvocationTargetException) {
                                            when (e.targetException) {
                                                is SecurityException, is IllegalArgumentException -> throw RootDispatchDenied()
                                                else -> throw e
                                            }
                                        }
                                    } finally { event.recycle() }
                                }
                                safe.set(outcome.status != "UNCONFIRMED")
                                busy.set(false)
                                reply("DONE ${outcome.status} ${outcome.noteDown ?: -1} ${outcome.noteUp ?: -1}")
                            }
                            worker.set(playback)
                            playback.start()
                        }
                    }
                    line.startsWith("PLAY ") -> reply("DONE UNCONFIRMED -1 -1")
                }
            }
        } finally {
            stopped.set(true)
            worker.get()?.let {
                LockSupport.unpark(it)
                it.join()
            }
            volume?.close()
            reply(if (safe.get()) "CLOSED" else "UNCONFIRMED")
        }
    }

    private fun parse(text: String): List<RootStroke>? = runCatching {
        require(text.length <= 200)
        text.split(';').map { stroke ->
            val values = stroke.split(',')
            require(values.size == 4)
            RootStroke(values[0].toFloat(), values[1].toFloat(), values[2].toLong(), values[3].toLong()).also {
                require(it.x.isFinite() && it.y.isFinite() && it.x >= 0 && it.y >= 0)
                require(it.startMs >= 0 && it.durationMs in 1..MAX_GESTURE_DURATION_MS)
                require(it.startMs <= MAX_GESTURE_DURATION_MS - it.durationMs)
            }
        }.also { require(it.size in 1..3) }
    }.getOrNull()

    private fun motion(kind: RootTouchKind, id: Int, pointers: Map<Int, RootStroke>, down: Long, time: Long): MotionEvent {
        val index = pointers.keys.indexOf(id)
        val action = when (kind) {
            RootTouchKind.DOWN -> if (pointers.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN
            RootTouchKind.UP -> if (pointers.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP
            RootTouchKind.CANCEL -> MotionEvent.ACTION_CANCEL
        }
        val indexedAction = if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP)
            action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        val properties = pointers.keys.map { pointerId ->
            MotionEvent.PointerProperties().apply { this.id = pointerId; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }.toTypedArray()
        val coordinates = pointers.map { (pointerId, point) ->
            MotionEvent.PointerCoords().apply {
                x = point.x
                y = point.y
                pressure = if ((kind == RootTouchKind.UP && id == pointerId) || kind == RootTouchKind.CANCEL) 0f else 1f
                size = 1f
            }
        }.toTypedArray()
        return MotionEvent.obtain(
            down, time, indexedAction, pointers.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }
}
