package com.huanhren.deltaharmonica.data

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface ImportSyncedOutput : java.io.Closeable {
    fun write(bytes: ByteArray)
    fun sync()
}

/**
 * Atomic renames require both paths on the same filesystem. The journal file is synced,
 * but parent directories are not synced here; namespace atomicity is not a guarantee
 * that directory entries survive power loss. No destructive power-loss test was run.
 */
interface ImportFileOperations {
    fun createDirectory(directory: File): Boolean
    fun writeFile(file: File, bytes: ByteArray)
    fun openScriptOutput(file: File): java.io.OutputStream
    fun openJournalOutput(file: File): ImportSyncedOutput
    fun readFile(file: File): ByteArray
    fun sha256(bytes: ByteArray): String
    fun exists(file: File): Boolean
    fun deleteTree(file: File): Boolean
    fun publishDirectory(source: File, target: File)
    fun replaceJournal(source: File, target: File)
}

object RealImportFileOperations : ImportFileOperations {
    override fun createDirectory(directory: File) = directory.mkdir()
    override fun writeFile(file: File, bytes: ByteArray) = file.writeBytes(bytes)
    override fun openScriptOutput(file: File): java.io.OutputStream = java.io.FileOutputStream(file)
    override fun openJournalOutput(file: File): ImportSyncedOutput {
        val stream = java.io.FileOutputStream(file)
        return object : ImportSyncedOutput {
            override fun write(bytes: ByteArray) = stream.write(bytes)
            override fun sync() = stream.fd.sync()
            override fun close() = stream.close()
        }
    }
    override fun readFile(file: File) = file.readBytes()
    override fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    override fun exists(file: File) = file.exists()
    override fun deleteTree(file: File) = file.deleteRecursively()
    override fun publishDirectory(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
    override fun replaceJournal(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
