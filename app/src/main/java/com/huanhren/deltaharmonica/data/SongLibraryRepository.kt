package com.huanhren.deltaharmonica.data

import com.huanhren.deltaharmonica.midi.MidiParser
import com.huanhren.deltaharmonica.midi.PerformanceScript
import com.huanhren.deltaharmonica.midi.ScriptCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

@Serializable enum class SongSourceType { MIDI, JSON }

@Serializable data class SongLibraryItem(
    val id: String, val displayName: String, val originalFileName: String,
    val sourceType: SongSourceType, val createdAt: Long, val updatedAt: Long,
    val originalArtifact: String, val playbackScriptArtifact: String,
    val eventCount: Int, val durationMs: Long?, val sourceTrack: Int?,
    val monophonyPolicy: String?, val transpose: Int?, val warnings: List<String>,
    val schemaVersion: Int = 1, val contentHash: String, val scriptHash: String,
    val scriptVersion: Int? = null,
)

/** Private per-song directories are published only after every artifact and its metadata are written. */
class SongLibraryRepository(private val root: File, private val operations: ImportFileOperations = RealImportFileOperations) {
    private val json = Json { encodeDefaults = true }
    private val journals = ImportJournalStore(root, operations)
    private var recoveryIssues: Map<String, String> = emptyMap()
    private val mutableItems = MutableStateFlow<List<SongLibraryItem>>(emptyList())
    val items = mutableItems.asStateFlow()
    private val mutableProblems = MutableStateFlow<Map<String, String>>(emptyMap())
    val problems = mutableProblems.asStateFlow()
    companion object { private val lock = Any(); private const val MAX_BYTES = 2_000_000 }

    private fun hash(bytes: ByteArray): String = operations.sha256(bytes)
    private fun songDir(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "无效曲目 ID" }
        return File(root, id)
    }
    private fun metadata(dir: File): SongLibraryItem {
        val item = json.decodeFromString<SongLibraryItem>(operations.readFile(File(dir, "metadata.json")).toString(Charsets.UTF_8))
        require(item.schemaVersion == 1 && item.id == dir.name && item.originalArtifact == "original" && item.playbackScriptArtifact in setOf("original", "script.json") && (item.playbackScriptArtifact != "original" || item.sourceType == SongSourceType.JSON)) { "不支持的曲库记录" }
        return item
    }
    private fun scan(): List<SongLibraryItem> = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
        .mapNotNull { runCatching { metadata(it) }.getOrNull() }.sortedWith(compareByDescending<SongLibraryItem> { it.createdAt }.thenBy { it.id })
    private fun publish() {
        val items = scan()
        val invalidRecords = root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
            .filter { runCatching { UUID.fromString(it.name).toString() == it.name }.getOrDefault(false) }
            .mapNotNull { dir -> runCatching { metadata(dir) }.exceptionOrNull()?.let { dir.name to "曲库记录无法读取，已保留原文件" } }.toMap()
        mutableProblems.value = recoveryIssues + invalidRecords + items.mapNotNull { item ->
            val issue = try {
                val dir = songDir(item.id)
                val original = File(dir, item.originalArtifact).readBytes()
                check(hash(original) == item.contentHash) { "原始文件校验失败" }
                val script = File(dir, item.playbackScriptArtifact).readBytes()
                check(hash(script) == item.scriptHash) { "演奏脚本校验失败" }
                val decoded = ScriptCodec.decode(script.toString(Charsets.UTF_8))
                check(decoded.events.isNotEmpty() && decoded.events.size == item.eventCount) { "演奏脚本事件无效" }
                null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { e }
            issue?.let { item.id to (it.localizedMessage ?: "文件不可用") }
        }.toMap()
        mutableItems.value = items
    }
    private fun verifyPublished(dir: File, entry: ImportJournal) {
        val item = metadata(dir)
        require(item.id == entry.songId && item.contentHash == entry.originalHash && item.scriptHash == entry.scriptHash) { "事务与曲目元数据不匹配" }
        val original = operations.readFile(File(dir,item.originalArtifact))
        val script = operations.readFile(File(dir,item.playbackScriptArtifact))
        require(hash(original) == entry.originalHash && hash(script) == entry.scriptHash) { "事务文件摘要不匹配" }
        val decoded = ScriptCodec.decode(script.toString(Charsets.UTF_8))
        require(decoded.events.isNotEmpty() && decoded.events.size == item.eventCount) { "事务脚本无效" }
    }
    private fun cleanVerifiedResidual(temp: File, final: File, context: kotlin.coroutines.CoroutineContext) {
        if (!operations.exists(temp)) return
        require(!Files.isSymbolicLink(temp.toPath()) && temp.isDirectory) { "临时目录归属不明，已保留" }
        val files = requireNotNull(temp.listFiles()) { "无法读取临时目录" }
        require(files.all { it.isFile && !Files.isSymbolicLink(it.toPath()) &&
            it.name in setOf("original", "script.json", "metadata.json") &&
            File(final,it.name).isFile && operations.readFile(it).contentEquals(operations.readFile(File(final,it.name))) }) { "临时目录内容无法确认，已保留" }
        context.ensureActive()
        check(operations.deleteTree(temp)) { "无法清理已验证的事务临时目录" }
    }
    private fun recoverLocked(context: kotlin.coroutines.CoroutineContext) {
        val issues = mutableMapOf<String,String>()
        journals.ids().forEach { id ->
            try {
                context.ensureActive()
                val entry = journals.read(id)
                context.ensureActive()
                val final = songDir(entry.songId)
                val temp = File(root,entry.tempDirectory)
                when (entry.state) {
                    ImportJournalState.PREPARING, ImportJournalState.FILES_READY -> {
                        if (operations.exists(final)) {
                            verifyPublished(final,entry)
                            context.ensureActive()
                            cleanVerifiedResidual(temp, final, context)
                            context.ensureActive()
                            journals.write(entry.copy(state=ImportJournalState.COMMITTED,updatedAt=System.currentTimeMillis()))
                        } else {
                            if (operations.exists(temp)) {
                                require(!Files.isSymbolicLink(temp.toPath()) && temp.isDirectory && temp.listFiles()?.all { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name in setOf("original","script.json","metadata.json") } == true) { "临时目录包含未知文件，已保留" }
                                context.ensureActive()
                                check(operations.deleteTree(temp)) { "无法清理事务临时目录" }
                            }
                            context.ensureActive()
                            journals.write(entry.copy(state=ImportJournalState.ROLLED_BACK,updatedAt=System.currentTimeMillis()))
                        }
                    }
                    ImportJournalState.COMMITTED -> {
                        verifyPublished(final,entry)
                        context.ensureActive()
                        cleanVerifiedResidual(temp, final, context)
                    }
                    ImportJournalState.ROLLED_BACK -> Unit
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { issues["transaction:$id"] = "事务恢复失败，文件已保留：${e.localizedMessage}" }
        }
        recoveryIssues = issues
    }
    suspend fun recoverPendingTransactions() = withContext(Dispatchers.IO) { val context = currentCoroutineContext(); synchronized(lock) { root.mkdirs(); recoverLocked(context); context.ensureActive(); publish() } }
    suspend fun refresh() = recoverPendingTransactions()
    suspend fun cleanupExpiredCandidates(now: Long) = withContext(Dispatchers.IO) { synchronized(lock) {
        val entries = root.listFiles().orEmpty()
        val tracked = runCatching { journals.ids().map { journals.read(it).tempDirectory }.toSet() }.getOrNull()
        entries.filter { file ->
            file.name.startsWith(".candidate-") && file.isFile && !Files.isSymbolicLink(file.toPath()) &&
                runCatching { candidate(file.name) }.isSuccess && file.lastModified() > 0 && file.lastModified() < now - 86_400_000L
        }.forEach { it.delete() }
        entries.filter { dir ->
            val suffix = dir.name.removePrefix(".pending-")
            dir.name.startsWith(".pending-") && dir.isDirectory && !Files.isSymbolicLink(dir.toPath()) &&
                tracked != null && dir.name !in tracked &&
                runCatching { UUID.fromString(suffix).toString() == suffix }.getOrDefault(false) &&
                dir.lastModified() > 0 && dir.lastModified() < now - 86_400_000L &&
                dir.listFiles()?.all { it.name in setOf("original", "script.json", "metadata.json") && it.isFile && !Files.isSymbolicLink(it.toPath()) } == true
        }.forEach { dir ->
            dir.listFiles().orEmpty().forEach { it.delete() }
            dir.delete()
        }
    } }
    private fun candidate(token: String): File {
        require(token.startsWith(".candidate-") && runCatching { UUID.fromString(token.removePrefix(".candidate-")).toString() == token.removePrefix(".candidate-") }.getOrDefault(false)) { "无效的导入文件" }
        return File(root, token)
    }
    suspend fun stageMidi(bytes: ByteArray): String = withContext(Dispatchers.IO) { synchronized(lock) {
        require(bytes.size in 1..MAX_BYTES) { "文件大小无效" }; MidiParser.parse(bytes); root.mkdirs()
        val token = ".candidate-${UUID.randomUUID()}"; val file = candidate(token)
        try { file.writeBytes(bytes); token } catch (e: Exception) { file.delete(); throw e }
    } }
    suspend fun discardCandidate(token: String) = withContext(Dispatchers.IO) { synchronized(lock) { candidate(token).delete() } }
    suspend fun addStagedMidi(name: String, token: String, script: PerformanceScript, warnings: List<String>): SongLibraryItem {
        try {
            val bytes = withContext(Dispatchers.IO) { synchronized(lock) { candidate(token).readBytes() } }
            return add(name, SongSourceType.MIDI, bytes, script, warnings)
        } finally {
            withContext(NonCancellable) { discardCandidate(token) }
        }
    }
    suspend fun get(id: String): SongLibraryItem? = withContext(Dispatchers.IO) { synchronized(lock) {
        val dir = songDir(id); if (dir.isDirectory) metadata(dir) else null
    } }
    suspend fun add(name: String, source: SongSourceType, original: ByteArray, script: PerformanceScript, warnings: List<String>): SongLibraryItem = withContext(Dispatchers.IO) { val context = currentCoroutineContext(); synchronized(lock) {
        require(name.isNotBlank() && original.size in 1..MAX_BYTES) { "文件名或大小无效" }
        val encoded = ScriptCodec.encode(script).toByteArray(Charsets.UTF_8)
        require(script.events.isNotEmpty()) { "脚本没有可播放事件" }
        when (source) {
            SongSourceType.MIDI -> MidiParser.parse(original)
            SongSourceType.JSON -> require(ScriptCodec.decode(original.toString(Charsets.UTF_8)) == script) { "JSON 脚本与原文件不一致" }
        }
        root.mkdirs()
        val originalHash = hash(original)
        val scriptBytes = if (source == SongSourceType.JSON) original else encoded
        val scriptHash = hash(scriptBytes)
        require(scan().none { it.contentHash == originalHash && it.scriptHash == scriptHash }) { "该曲目已导入" }
        val id = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
        val last = script.events.lastOrNull()
        val item = SongLibraryItem(id, name, name, source, now, now, "original", if (source == SongSourceType.JSON) "original" else "script.json", script.events.size,
            last?.let { it.startTimeMs + it.durationMs }, if (source == SongSourceType.MIDI) script.sourceTrack else null,
            if (source == SongSourceType.MIDI) script.monophonyPolicy.name else null,
            if (source == SongSourceType.MIDI) script.transpose else null, warnings, contentHash = originalHash, scriptHash = scriptHash, scriptVersion = script.schemaVersion)
        val temporary = File(root, ".pending-$id")
        var journal = ImportJournal(UUID.randomUUID().toString(), id, ImportJournalState.PREPARING, temporary.name, id, now, now, originalHash, scriptHash)
        journals.write(journal)
        context.ensureActive()
        check(operations.createDirectory(temporary)) { "无法创建临时目录" }
        var primaryFailure: Throwable? = null
        try {
            operations.writeFile(File(temporary, "original"), original)
            context.ensureActive()
            if (source == SongSourceType.MIDI) operations.openScriptOutput(File(temporary, "script.json")).use { it.write(scriptBytes) }
            context.ensureActive()
            operations.writeFile(File(temporary, "metadata.json"), json.encodeToString(item).toByteArray(Charsets.UTF_8))
            context.ensureActive()
            require(json.decodeFromString<SongLibraryItem>(operations.readFile(File(temporary,"metadata.json")).toString(Charsets.UTF_8)) == item) { "事务元数据校验失败" }
            require(hash(operations.readFile(File(temporary,"original"))) == originalHash && hash(operations.readFile(File(temporary,item.playbackScriptArtifact))) == scriptHash) { "事务文件校验失败" }
            require(ScriptCodec.decode(operations.readFile(File(temporary,item.playbackScriptArtifact)).toString(Charsets.UTF_8)) == script) { "事务脚本校验失败" }
            journal = journal.copy(state=ImportJournalState.FILES_READY,updatedAt=System.currentTimeMillis()); journals.write(journal)
            context.ensureActive()
            check(!operations.exists(songDir(id))) { "目标曲目已存在" }
            operations.publishDirectory(temporary, songDir(id))
            context.ensureActive()
            verifyPublished(songDir(id),journal)
            context.ensureActive()
            journal = journal.copy(state=ImportJournalState.COMMITTED,updatedAt=System.currentTimeMillis()); journals.write(journal)
            context.ensureActive()
            publish(); item
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                if (operations.exists(temporary)) check(operations.deleteTree(temporary)) { "无法清理事务临时目录" }
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original != null) original.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    } }
    suspend fun rename(id: String, name: String): SongLibraryItem = withContext(Dispatchers.IO) { synchronized(lock) {
        require(name.isNotBlank()) { "曲目名称不能为空" }
        val dir = songDir(id); val item = metadata(dir).copy(displayName = name.trim(), updatedAt = System.currentTimeMillis())
        val temp = File(dir, ".metadata-${UUID.randomUUID()}")
        try { temp.writeText(json.encodeToString(item)); Files.move(temp.toPath(), File(dir, "metadata.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        finally { temp.delete() }
        publish(); item
    } }
    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) { synchronized(lock) {
        val dir = songDir(id); if (!dir.exists()) false else {
            val item = metadata(dir)
            val ownedJournals = journals.ids().filter { journals.read(it).songId == id }
            val allowed = setOf("metadata.json", item.originalArtifact, item.playbackScriptArtifact)
            check(dir.listFiles()?.all { it.name in allowed && it.isFile && !Files.isSymbolicLink(it.toPath()) } == true) { "目录包含未知文件，已保留" }
            try {
                setOf(item.originalArtifact, item.playbackScriptArtifact).forEach { name ->
                    val file = File(dir,name); if (file.exists()) check(file.delete()) { "无法删除曲目文件：$name" }
                }
                check(File(dir,"metadata.json").delete()) { "无法删除曲目记录" }
                check(dir.delete()) { "曲目记录已删除，但目录清理失败" }
                ownedJournals.forEach { journals.remove(it) }
            } finally { publish() }
            true
        }
    } }
    suspend fun readOriginal(id: String): ByteArray = withContext(Dispatchers.IO) { synchronized(lock) {
        val dir = songDir(id); val item = metadata(dir); val bytes = File(dir, "original").readBytes()
        check(hash(bytes) == item.contentHash) { "原始文件校验失败" }; bytes
    } }
    suspend fun readScript(id: String): PerformanceScript = withContext(Dispatchers.IO) { synchronized(lock) {
        val dir = songDir(id); val item = metadata(dir); val bytes = File(dir, item.playbackScriptArtifact).readBytes()
        if (item.playbackScriptArtifact != item.originalArtifact) {
            check(hash(operations.readFile(File(dir, item.originalArtifact))) == item.contentHash) { "原始文件校验失败" }
        }
        check(hash(bytes) == item.scriptHash) { "演奏脚本校验失败" }
        ScriptCodec.decode(bytes.toString(Charsets.UTF_8)).also { check(it.events.isNotEmpty() && it.events.size == item.eventCount) { "演奏脚本无效" } }
    } }
}
