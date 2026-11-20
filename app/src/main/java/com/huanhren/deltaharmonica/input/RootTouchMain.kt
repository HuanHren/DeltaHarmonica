package com.huanhren.deltaharmonica.input

import android.os.SystemClock
import android.system.Os
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import com.huanhren.deltaharmonica.playback.ROOT_STROKE_DISPATCH_MARGIN_MS
import com.huanhren.deltaharmonica.playback.ROOT_RECOVERY_WINDOW_MS
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** Privileged process entry point. Reads commands while one worker owns the active touches. */
object RootTouchMain {
    @JvmStatic
    fun main(args: Array<String>) {
        check(Os.getuid() == 0)
        val targetUid = args[0].toInt()
        require(targetUid >= 0)
        RootHelperSession(targetUid).run(args.getOrNull(1) == "volume")
    }
}

private class RootHelperSession(targetUid: Int) {
    private val injector = RootMotionInjector(targetUid)
    private val worker = AtomicReference<Thread>()
    private val stopped = AtomicBoolean()
    private val busy = AtomicBoolean()
    private val safe = AtomicBoolean(true)
    private val volumeStopped = AtomicBoolean()
    private val paused = AtomicBoolean()
    private val currentSequence = AtomicLong()
    private val liveThrough = AtomicLong()
    private var lastSequence = 0L
    private var upload: RootSequenceUpload? = null
    private val output = RootHelperReplies(::requestStop)

    fun run(watchVolume: Boolean) {
        val volume = if (watchVolume) RootVolumeKeyMonitor.start(::volumeStop) else null
        if (watchVolume) output.offer(if (volume == null) "NO_VOLUME_MONITOR" else "VOLUME_READY")
        output.offer("READY ${RootSequenceProtocol.VERSION}")
        try {
            System.`in`.bufferedReader().forEachLine(::handleCommand)
        } finally {
            requestStop()
            worker.get()?.join()
            volume?.close()
            output.finish(if (safe.get()) "CLOSED" else "UNCONFIRMED")
        }
    }

    private fun volumeStop() {
        if (volumeStopped.compareAndSet(false, true)) {
            requestStop()
            output.offer("VOLUME ${SystemClock.uptimeMillis()}")
        }
    }

    private fun requestStop() {
        stopped.set(true)
        paused.set(false)
        worker.get()?.let(LockSupport::unpark)
    }

    private fun handleCommand(line: String) {
        when {
            line == "STOP" -> requestStop()
            line.startsWith("SEQ ") -> handleSequence(line)
            line.startsWith("PLAY ") -> handleGesture(line.removePrefix("PLAY "))
        }
    }

    private fun handleSequence(line: String) {
        val fields = line.split(' ', limit = 5)
        val id = fields.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 } ?: return
        val command = fields.getOrNull(2)
        val isActive = currentSequence.get() == id
        val hasUpload = lastSequence == id && upload != null
        when {
            command == "PING" && isActive -> {
                extendHeartbeat()
                reply(id, "PONG")
            }
            command == "STOP" && isActive -> requestStop()
            command == "STOP" && hasUpload -> {
                upload = null
                reply(id, "END STOPPED 0")
            }
            command == "PAUSE" && isActive -> {
                paused.set(true)
                worker.get()?.let(LockSupport::unpark)
            }
            command == "RESUME" && isActive -> {
                paused.set(false)
                worker.get()?.let(LockSupport::unpark)
            }
            command == "QUEUE" && !busy.get() && safe.get() && upload == null && id > lastSequence ->
                beginUpload(id, fields)
            command == "BLOCK" && !busy.get() && hasUpload -> appendUpload(id, fields)
            command == "RUN" && !busy.get() && safe.get() && hasUpload -> commitUpload(id, fields)
            command == "QUEUE" || command == "BLOCK" || command == "RUN" -> reply(id, "PROTOCOL_ERROR")
        }
    }

    private fun beginUpload(id: Long, fields: List<String>) {
        lastSequence = id
        val size = fields.getOrNull(3)?.toIntOrNull()
        when {
            volumeStopped.get() -> reply(id, "END STOPPED 0")
            fields.size != 4 || size == null || size !in 1..RootSequenceProtocol.MAX_NOTES ->
                reply(id, "END FAILED 0")
            else -> {
                upload = RootSequenceUpload(size)
                reply(id, "ACK 0")
            }
        }
    }

    private fun appendUpload(id: Long, fields: List<String>) {
        val pending = checkNotNull(upload)
        val first = fields.getOrNull(3)?.toIntOrNull()
        val payload = fields.getOrNull(4)
        when {
            volumeStopped.get() -> {
                upload = null
                reply(id, "END STOPPED 0")
            }
            first == null || payload == null || !pending.append(first, payload) -> {
                upload = null
                reply(id, "END FAILED 0")
            }
            else -> reply(id, "ACK ${pending.received}")
        }
    }

    private fun commitUpload(id: Long, fields: List<String>) {
        val steps = upload?.commit()
        upload = null
        val pauseInitially = fields.getOrNull(3)
        when {
            volumeStopped.get() -> reply(id, "END STOPPED 0")
            steps == null || fields.size != 4 || (pauseInitially != "0" && pauseInitially != "1") ->
                reply(id, "END FAILED 0")
            else -> startSequence(id, steps, pauseInitially == "1")
        }
    }

    private fun extendHeartbeat() {
        liveThrough.set(SystemClock.uptimeMillis() + HEARTBEAT_BUDGET_MS)
    }

    private fun checkActive(sequence: Boolean, now: Long) {
        if (stopped.get() || volumeStopped.get() || (sequence && now >= liveThrough.get())) {
            throw InterruptedException()
        }
    }

    private fun waitUntil(deadline: Long, sequence: Boolean, boundarySequenceId: Long? = null): Long {
        while (true) {
            val now = SystemClock.uptimeMillis()
            checkActive(sequence, now)
            if (paused.get() && boundarySequenceId != null) {
                val shift = waitForResume(boundarySequenceId)
                if (shift > 0) return shift
                continue
            }
            val remaining = deadline - now
            if (remaining <= 0) return 0
            LockSupport.parkNanos(minOf(remaining, 25L) * 1_000_000)
        }
    }

    private fun waitForResume(id: Long): Long {
        val from = SystemClock.uptimeMillis()
        reply(id, "PAUSED")
        while (paused.get()) {
            checkActive(sequence = true, now = SystemClock.uptimeMillis())
            LockSupport.parkNanos(20_000_000)
        }
        val now = SystemClock.uptimeMillis()
        checkActive(sequence = true, now = now)
        reply(id, "RESUMED")
        return now - from
    }

    private fun gesture(
        strokes: List<RootStroke>,
        scheduledStartMs: Long? = null,
        maxDownLatenessMs: Long = Long.MAX_VALUE,
    ): RootTouchOutcome = RootTouchTimeline.play(
        strokes, SystemClock::uptimeMillis,
        waitUntil = { waitUntil(it, sequence = scheduledStartMs != null) },
        scheduledStartMs = scheduledStartMs,
        maxDownLatenessMs = maxDownLatenessMs,
        sequential = scheduledStartMs != null,
        settlingSlackMs = ROOT_STROKE_DISPATCH_MARGIN_MS,
        emit = injector::emit,
    )

    private fun startSequence(id: Long, steps: List<RootSequenceStep>, pauseInitially: Boolean) {
        extendHeartbeat()
        currentSequence.set(id)
        startWorker("DeltaRootSequence", pauseInitially) {
            val result = runCatching {
                RootSequenceTimeline.play(
                    steps, SystemClock::uptimeMillis,
                    waitFor = { waitUntil(it, sequence = true, boundarySequenceId = id) },
                    playGesture = { gesture(it) },
                    onNote = { _, _ -> },
                    onStarted = { reply(id, "START $it") },
                playTimedGesture = { step, at -> gesture(step.strokes, at, step.maxLatenessMs + ROOT_RECOVERY_WINDOW_MS) },
                    onScheduledNote = { index, note, planned ->
                        reply(id, "EVENT $index ${note.status} $planned ${note.noteDown ?: -1} ${note.noteUp ?: -1}")
                    },
                )
            }.getOrElse { RootSequenceOutcome("UNCONFIRMED", 0) }
            safe.set(result.status != "UNCONFIRMED")
            currentSequence.set(0)
            busy.set(false)
            reply(id, "END ${result.status} ${result.completed}")
        }
    }

    private fun handleGesture(payload: String) {
        val strokes = RootSequenceProtocol.decodeStrokes(payload)
        when {
            volumeStopped.get() -> output.offer("DONE STOPPED -1 -1")
            busy.get() || !safe.get() || upload != null -> output.offer("DONE UNCONFIRMED -1 -1")
            strokes == null -> output.offer("DONE FAILED -1 -1")
            else -> startWorker("DeltaRootGesture", pauseInitially = false) {
                val outcome = runCatching { gesture(strokes) }
                    .getOrElse { RootTouchOutcome("UNCONFIRMED", null, null) }
                safe.set(outcome.status != "UNCONFIRMED")
                busy.set(false)
                output.offer("DONE ${outcome.status} ${outcome.noteDown ?: -1} ${outcome.noteUp ?: -1}")
            }
        }
    }

    private fun startWorker(name: String, pauseInitially: Boolean, task: Runnable) {
        stopped.set(false)
        paused.set(pauseInitially)
        busy.set(true)
        val playback = Thread(task, name)
        worker.set(playback)
        playback.start()
    }

    private fun reply(id: Long, payload: String) {
        output.offer("SEQ $id $payload")
    }

    private companion object {
        const val HEARTBEAT_BUDGET_MS = 4_000L
    }
}

private class RootMotionInjector(targetUid: Int) {
    private val uid = targetUid
    private val managerClass = Class.forName("android.hardware.input.InputManagerGlobal")
    private val manager = managerClass.getMethod("getInstance").invoke(null)
    private val inject = managerClass.getMethod(
        "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
    )

    fun emit(kind: RootTouchKind, id: Int, pointers: Map<Int, RootStroke>, down: Long, time: Long): Boolean {
        val event = motion(kind, id, pointers, down, time)
        try {
            // WAIT_FOR_RESULT with a target UID. Never retarget an uncertain UP or CANCEL.
            return try { inject.invoke(manager, event, 1, uid) == true }
            catch (e: InvocationTargetException) {
                when (e.targetException) {
                    is SecurityException, is IllegalArgumentException -> throw RootDispatchDenied()
                    else -> throw e
                }
            }
        } finally { event.recycle() }
    }

    private fun motion(kind: RootTouchKind, id: Int, pointers: Map<Int, RootStroke>, down: Long, time: Long): MotionEvent {
        val index = pointers.keys.indexOf(id)
        check(index >= 0)
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

/** Output backpressure aborts scheduling instead of blocking the touch worker holding a pointer. */
private class RootHelperReplies(onFailure: Runnable) {
    private val failure = onFailure
    private val queue = ArrayBlockingQueue<String>(RootSequenceProtocol.BUFFER_SIZE)
    private val broken = AtomicBoolean()
    private val writer = Thread(::flushReplies, "DeltaRootReplies").apply { isDaemon = true; start() }

    private fun flushReplies() {
        while (true) {
            val line = queue.take()
            System.out.println("DELTA $line")
            if (System.out.checkError()) {
                broken.set(true)
                failure.run()
                break
            }
            if (line == "CLOSED" || line == "UNCONFIRMED") break
        }
    }

    fun offer(line: String) {
        if (broken.get() || !queue.offer(line)) failure.run()
    }

    fun finish(line: String) {
        if (!broken.get() && queue.offer(line, 500L, TimeUnit.MILLISECONDS)) writer.join(500L)
    }
}
