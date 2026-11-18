package com.huanhren.deltaharmonica.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.coroutineContext

enum class ImportPhase { IDLE, READING, PREFLIGHT, PARSING, CONVERTING, VALIDATING, SAVING, COMMITTED, READY, CANCELLED, FAILED }
data class ImportUiState(val taskId: String? = null, val phase: ImportPhase = ImportPhase.IDLE,
    val message: String = "", val songId: String? = null, val committed: Boolean = false,
    val active: Boolean = false, val rejection: String? = null)

/** A single task survives observers; owner cancellation cancels its work. */
class ImportCoordinator(private val scope: CoroutineScope) {
    private val lock = Any()
    private var job: Job? = null
    private val mutableState = MutableStateFlow(ImportUiState())
    val state = mutableState.asStateFlow()

    inner class Task internal constructor(private val id: String) {
        suspend fun update(phase: ImportPhase, message: String, songId: String? = null, committed: Boolean = false) {
            coroutineContext.ensureActive()
            synchronized(lock) {
                if (mutableState.value.taskId == id && mutableState.value.active && job?.isActive == true)
                    mutableState.value = ImportUiState(id, phase, message, songId, committed, true)
            }
        }
        suspend fun checkActive() = coroutineContext.ensureActive()
    }
    fun start(work: suspend Task.() -> Unit): Boolean = synchronized(lock) {
        if (job?.isActive == true) {
            mutableState.value = mutableState.value.copy(rejection = "已有导入任务正在进行，请等待或取消后重试")
            return false
        }
        val id = UUID.randomUUID().toString()
        mutableState.value = ImportUiState(id, ImportPhase.READING, "准备导入…", active = true)
        val next = scope.launch(start = CoroutineStart.LAZY) {
            val task = Task(id)
            try {
                task.work()
                coroutineContext.ensureActive()
                synchronized(lock) { if (mutableState.value.taskId == id) mutableState.value = mutableState.value.copy(active = false) }
            } catch (e: CancellationException) {
                synchronized(lock) { if (mutableState.value.taskId == id) mutableState.value = mutableState.value.copy(phase = ImportPhase.CANCELLED, message = if (mutableState.value.committed) "导入已保存，后续操作已取消" else "导入已取消；如文件已发布，将在下次刷新时恢复", active = false) }
                throw e
            } catch (e: Exception) {
                synchronized(lock) { if (mutableState.value.taskId == id) mutableState.value = mutableState.value.copy(phase = ImportPhase.FAILED, message = (if (mutableState.value.committed) "曲目已保存，加载失败：" else "导入失败：") + (e.localizedMessage ?: "未知错误"), active = false) }
            } finally {
                synchronized(lock) { if (mutableState.value.taskId == id) job = null }
            }
        }
        job = next
        next.invokeOnCompletion { cause ->
            synchronized(lock) {
                if (mutableState.value.taskId == id && mutableState.value.active && cause is CancellationException) {
                    mutableState.value = mutableState.value.copy(phase = ImportPhase.CANCELLED, message = "导入已取消", active = false)
                }
                if (job === next) job = null
            }
        }
        next.start()
        true
    }
    fun cancel() = synchronized(lock) { job?.cancel() }
}
