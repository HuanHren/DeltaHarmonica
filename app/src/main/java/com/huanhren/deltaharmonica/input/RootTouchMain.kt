package com.huanhren.deltaharmonica.input

import android.os.SystemClock
import android.system.Os
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import com.huanhren.deltaharmonica.playback.ROOT_STROKE_DISPATCH_MARGIN_MS
import com.huanhren.deltaharmonica.playback.ROOT_RECOVERY_WINDOW_MS
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.UUID
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
        val sessionId = args[1].also { require(RootSequenceProtocol.validSessionId(it)) }
        val identity = requireNotNull(RootSequenceProtocol.decodeProcessStat(File("/proc/self/stat").readText()))
        RootHelperSession(targetUid, sessionId).run(identity, args.getOrNull(2) == "volume")
    }
}

private class RootHelperSession(targetUid: Int, private val sessionId: String) {
    private val injector = RootMotionInjector(targetUid)
    private val dispatcherId = UUID.randomUUID().toString()
    private val watchdog = RootDispatcherWatchdog(SystemClock::uptimeMillis)
    private val work = ArrayBlockingQueue<Runnable>(1)
    private val closing = AtomicBoolean()
    private val shutdownComplete = AtomicBoolean()
    private val stopped = AtomicBoolean()
    private val transportInterrupted = AtomicBoolean()
    private val busy = AtomicBoolean()
    private val safe = AtomicBoolean(true)
    private val uncertainPointerIds = AtomicReference<Set<Int>?>()
    private val volumeStopped = AtomicBoolean()
    private val paused = AtomicBoolean()
    private val currentSequence = AtomicLong()
    private val currentOperation = AtomicLong()
    private val liveThrough = AtomicLong()
    private val volumeMonitor = AtomicReference<RootVolumeKeyMonitor?>()
    @Volatile private var volumeSetup: Thread? = null
    private var lastOperation = 0L
    private var upload: RootSequenceUpload? = null
    private val output = RootHelperReplies(sessionId, dispatcherId, ::endTransport)
    private val worker = Thread(::dispatch, "DeltaRootDispatcher").apply { isDaemon = true }

    fun run(identity: RootHelperIdentity, watchVolume: Boolean) {
        worker.start()
        val supervisor = Thread(::supervise, "DeltaRootWatchdog").apply { isDaemon = true; start() }
        output.offer(0, "READY ${RootSequenceProtocol.VERSION} ${identity.pid} ${identity.startTicks}")
        if (watchVolume) startVolumeMonitor()
        try {
            System.`in`.bufferedReader().use { input ->
                while (!transportInterrupted.get()) handleCommand(input.readLine() ?: break)
            }
        } finally {
            endTransport()
            closing.set(true)
            try {
                worker.join(RootSequenceProtocol.ACK_TIMEOUT_MS)
                closeVolumeMonitor()
                if (!worker.isAlive && watchdog.canConfirmRelease()) output.finish(0, cleanupReceipt())
            } finally {
                shutdownComplete.set(true)
                supervisor.join(SUPERVISOR_JOIN_MS)
            }
        }
    }

    private fun dispatch() {
        try {
            while (!closing.get() || work.isNotEmpty()) work.poll(25, TimeUnit.MILLISECONDS)?.run()
        } catch (_: Exception) {
            // The watchdog publishes worker failure and terminates this helper.
        }
    }

    private fun supervise() {
        while (!shutdownComplete.get()) {
            // Heartbeats attest to the control reader. In-flight calls and STOP are timed here.
            expireHeartbeat()
            val failure = watchdog.fault(worker.isAlive || (closing.get() && watchdog.canConfirmRelease()))
            if (failure != null) {
                val pointers = failure.possiblePointerIds
                val suffix = if (pointers.isEmpty()) "" else " POINTERS ${pointers.sorted().joinToString(",")}"
                output.finish(failure.operation, "FAULT ${failure.reason.name}$suffix")
                // A Binder call cannot be interrupted reliably. Kill only this helper process.
                terminateHelper()
                return
            }
            if (watchdog.transportExpired()) {
                closing.set(true)
                worker.join(50L)
                if (!worker.isAlive && watchdog.canConfirmRelease()) output.finish(0, cleanupReceipt())
                // The command reader might still be blocked in readLine after lease expiry.
                terminateHelper()
                return
            }
            LockSupport.parkNanos(10_000_000)
        }
    }

    @Synchronized
    private fun expireHeartbeat() {
        if (currentSequence.get() != 0L && SystemClock.uptimeMillis() >= liveThrough.get()) endTransport()
    }

    private fun cleanupReceipt(): String {
        val pointers = uncertainPointerIds.get()
        return when {
            safe.get() -> "CLOSED"
            pointers.isNullOrEmpty() -> "UNCONFIRMED"
            else -> "UNCONFIRMED POINTERS ${pointers.sorted().joinToString(",")}"
        }
    }

    private fun closeVolumeMonitor() {
        val starter = volumeSetup
        starter?.interrupt()
        synchronized(this) { volumeMonitor.getAndSet(null) }?.close()
        starter?.join(100L)
    }

    private fun startVolumeMonitor() {
        val starter = Thread({
            val monitor = RootVolumeKeyMonitor.start(::volumeStop)
            val installed = synchronized(this) {
                if (transportInterrupted.get()) false
                else {
                    volumeMonitor.set(monitor)
                    output.offer(0, if (monitor == null) "NO_VOLUME_MONITOR" else "VOLUME_READY")
                    true
                }
            }
            if (!installed) monitor?.close()
        }, "DeltaRootVolumeSetup").apply { isDaemon = true }
        volumeSetup = starter
        starter.start()
    }

    private fun terminateHelper() {
        endTransport()
        try { closeVolumeMonitor() }
        finally { android.os.Process.killProcess(android.os.Process.myPid()) }
    }

    @Synchronized
    private fun volumeStop() {
        if (volumeStopped.get() || transportInterrupted.get()) return
        // The worker must be unable to report the stop before the cause is queued.
        if (output.offer(0, "VOLUME ${SystemClock.uptimeMillis()}")) {
            volumeStopped.set(true)
            requestStop()
        }
    }

    private fun requestStop() {
        watchdog.requestStop(InterruptedException())
        stopped.set(true)
        paused.set(false)
        LockSupport.unpark(worker)
    }

    private fun endTransport() {
        watchdog.endTransport()
        transportInterrupted.set(true)
        paused.set(false)
        LockSupport.unpark(worker)
    }

    @Synchronized
    private fun handleCommand(line: String) {
        val frame = RootSequenceProtocol.decodeFrame(line, sessionId) ?: return
        if (frame.dispatcherId != dispatcherId || transportInterrupted.get()) return
        val id = frame.operation
        val command = frame.payload
        when {
            id == 0L && command == "PING" && !busy.get() -> work.offer { output.offer(0, "PONG") }
            id == 0L -> Unit
            command == "STOP" && currentOperation.get() == id -> requestStop()
            command == "STOP" && !busy.get() && upload == null && id > lastOperation -> {
                lastOperation = id
                output.offer(id, "DONE STOPPED -1 -1")
            }
            command.startsWith("SEQ ") -> handleSequence(id, command)
            command.startsWith("PLAY ") && id > lastOperation -> handleGesture(id, command.removePrefix("PLAY "))
        }
    }

    private fun handleSequence(id: Long, line: String) {
        val fields = line.split(' ', limit = 5)
        if (fields.getOrNull(1)?.toLongOrNull() != id || id < lastOperation) return
        val command = fields.getOrNull(2)
        val isActive = currentSequence.get() == id
        val hasUpload = lastOperation == id && upload != null
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
            command == "STOP" && !busy.get() && upload == null && id > lastOperation -> {
                // Priority STOP can arrive before a superseded QUEUE reaches the writer.
                lastOperation = id
                reply(id, "END STOPPED 0")
            }
            command == "PAUSE" && isActive -> {
                paused.set(true)
                LockSupport.unpark(worker)
            }
            command == "RESUME" && isActive -> {
                paused.set(false)
                LockSupport.unpark(worker)
            }
            command == "QUEUE" && !busy.get() && safe.get() && upload == null && id > lastOperation ->
                beginUpload(id, fields)
            command == "BLOCK" && !busy.get() && hasUpload -> appendUpload(id, fields)
            command == "RUN" && !busy.get() && safe.get() && hasUpload -> commitUpload(id, fields)
            command == "QUEUE" || command == "BLOCK" || command == "RUN" -> reply(id, "PROTOCOL_ERROR")
        }
    }

    private fun beginUpload(id: Long, fields: List<String>) {
        lastOperation = id
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
        if (stopped.get() || volumeStopped.get()) throw InterruptedException()
        if (transportInterrupted.get() || (sequence && now >= liveThrough.get())) {
            throw RootTransportInterrupted()
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
        emit = ::emit,
    ).also { outcome ->
        if (outcome.status == "UNCONFIRMED") uncertainPointerIds.set(outcome.uncertainPointerIds)
    }

    private fun emit(kind: RootTouchKind, id: Int, pointers: Map<Int, RootStroke>, down: Long, time: Long): Boolean {
        watchdog.beginNative(kind, pointers.keys)
        var accepted = false
        var denied = false
        try {
            return injector.emit(kind, id, pointers, down, time).also { accepted = it }
        } catch (rejection: RootDispatchDenied) {
            denied = true
            throw rejection
        } finally {
            if (!watchdog.endNative(kind, id, accepted, denied)) throw RootTransportInterrupted()
        }
    }

    private fun startSequence(id: Long, steps: List<RootSequenceStep>, pauseInitially: Boolean) {
        startWorker(id, pauseInitially, sequence = true) {
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
            finishOperation(id, result.status) { reply(id, "END ${result.status} ${result.completed}") }
        }
    }

    private fun handleGesture(id: Long, payload: String) {
        if (busy.get() || upload != null) return
        lastOperation = id
        val strokes = RootSequenceProtocol.decodeStrokes(payload)
        when {
            volumeStopped.get() -> output.offer(id, "DONE STOPPED -1 -1")
            !safe.get() -> output.offer(id, "DONE UNCONFIRMED -1 -1")
            strokes == null -> output.offer(id, "DONE FAILED -1 -1")
            else -> startWorker(id, pauseInitially = false) {
                val outcome = runCatching { gesture(strokes) }
                    .getOrElse { RootTouchOutcome("UNCONFIRMED", null, null) }
                finishOperation(id, outcome.status) {
                    output.offer(id, "DONE ${outcome.status} ${outcome.noteDown ?: -1} ${outcome.noteUp ?: -1}")
                }
            }
        }
    }

    private fun startWorker(id: Long, pauseInitially: Boolean, sequence: Boolean = false, task: Runnable) {
        if (!watchdog.start(id)) return
        if (sequence) {
            extendHeartbeat()
            currentSequence.set(id)
        }
        stopped.set(false)
        uncertainPointerIds.set(null)
        paused.set(pauseInitially)
        busy.set(true)
        currentOperation.set(id)
        if (transportInterrupted.get()) endTransport()
        check(work.offer(task))
    }

    @Synchronized
    private fun finishOperation(id: Long, status: String, reply: () -> Unit) {
        if (!watchdog.finish(id)) return
        safe.set(status != "UNCONFIRMED")
        currentSequence.set(0)
        currentOperation.set(0)
        busy.set(false)
        reply()
    }

    private fun reply(id: Long, payload: String) {
        output.offer(id, "SEQ $id $payload")
    }

    private companion object {
        const val HEARTBEAT_BUDGET_MS = 4_000L
        const val SUPERVISOR_JOIN_MS = 300L
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
private class RootHelperReplies(
    private val sessionId: String,
    private val dispatcherId: String,
    private val onFailure: Runnable,
) {
    private data class Reply(val operation: Long, val payload: String, val terminal: Boolean = false)

    private val queue = ArrayBlockingQueue<Reply>(RootSequenceProtocol.BUFFER_SIZE)
    private val broken = AtomicBoolean()
    private val terminalQueued = AtomicBoolean()
    val failed get() = broken.get()
    private val writer = Thread(::flushReplies, "DeltaRootReplies").apply { isDaemon = true; start() }

    private fun flushReplies() {
        while (true) {
            val reply = queue.take()
            System.out.println(RootSequenceProtocol.encodeFrame(sessionId, dispatcherId, reply.operation, reply.payload))
            if (System.out.checkError()) {
                fail()
                break
            }
            if (reply.terminal) break
        }
    }

    fun offer(operation: Long, payload: String): Boolean {
        if (!failed && queue.offer(Reply(operation, payload))) return true
        fail()
        return false
    }

    fun finish(operation: Long, payload: String) {
        if (!failed && terminalQueued.compareAndSet(false, true) &&
            queue.offer(Reply(operation, payload, terminal = true), 80L, TimeUnit.MILLISECONDS)
        ) {
            writer.join(120L)
        }
    }

    private fun fail() {
        if (broken.compareAndSet(false, true)) onFailure.run()
    }
}
