package com.huanhren.deltaharmonica.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable enum class ImportJournalState { PREPARING, FILES_READY, COMMITTED, ROLLED_BACK }

@Serializable data class ImportJournal(
    val transactionId: String, val songId: String, val state: ImportJournalState,
    val tempDirectory: String, val finalDirectory: String, val createdAt: Long, val updatedAt: Long,
    val originalHash: String, val scriptHash: String, val schemaVersion: Int = 1,
)

/** Independent versioned journal; never stores original files or scripts. */
class ImportJournalStore(root: File, private val operations: ImportFileOperations = RealImportFileOperations) {
    private val directory = File(root, ".transactions")
    private val json = Json { encodeDefaults = true }
    private fun validId(id: String) = runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)
    private fun validate(entry: ImportJournal) {
        require(entry.schemaVersion == 1 && validId(entry.transactionId) && validId(entry.songId)) { "不支持的事务记录" }
        require(entry.tempDirectory == ".pending-${entry.songId}" && entry.finalDirectory == entry.songId) { "事务目录无效" }
    }
    fun write(entry: ImportJournal) {
        validate(entry)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建事务日志目录" }
        val target = File(directory, "${entry.transactionId}.json")
        val temp = File(directory, ".${entry.transactionId}-${UUID.randomUUID()}.tmp")
        try {
            operations.openJournalOutput(temp).use { output ->
                output.write(json.encodeToString(entry).toByteArray(Charsets.UTF_8))
                output.sync()
            }
            operations.replaceJournal(temp, target)
        } finally { temp.delete() }
    }
    fun read(id: String): ImportJournal {
        require(validId(id)) { "无效的事务 ID" }
        val entry = json.decodeFromString<ImportJournal>(operations.readFile(File(directory, "$id.json")).toString(Charsets.UTF_8))
        validate(entry); require(entry.transactionId == id) { "事务 ID 不匹配" }; return entry
    }
    fun ids(): List<String> = directory.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }
        .map { it.name.removeSuffix(".json") }.sorted()
    fun remove(id: String) {
        require(validId(id))
        val file = File(directory, "$id.json")
        check(!file.exists() || file.delete()) { "无法删除事务日志" }
    }
}
