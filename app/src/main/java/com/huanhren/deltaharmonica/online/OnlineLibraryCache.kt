package com.huanhren.deltaharmonica.online

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal class OnlineLibraryCache(private val root: File) {
    data class Record(val text: String, val expiresAt: Long) {
        fun isFresh(now: Long) = now < expiresAt
    }

    private var generation = 0L

    @Synchronized
    fun generation(): Long = generation

    @Synchronized
    fun read(name: String, limit: Int): Record? {
        val file = File(root, name)
        if (!file.isFile) return null
        return try {
            if (file.length() !in HEADER_BYTES.toLong()..(limit.toLong() + HEADER_BYTES)) {
                throw IOException("Invalid cache size")
            }
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != FORMAT_VERSION) throw IOException("Invalid cache version")
                val expiresAt = input.readLong()
                val length = input.readInt()
                if (length !in 1..limit) throw IOException("Invalid cache record length")
                val digest = ByteArray(DIGEST_BYTES).also(input::readFully)
                val bytes = ByteArray(length).also(input::readFully)
                if (input.read() != -1 || !MessageDigest.isEqual(digest, hash(expiresAt, bytes))) {
                    throw IOException("Cache checksum mismatch")
                }
                Record(bytes.toString(Charsets.UTF_8), expiresAt)
            }
        } catch (_: IOException) {
            file.delete()
            null
        }
    }

    @Synchronized
    fun write(name: String, record: Record?, epoch: Long, limit: Int) {
        if (generation != epoch) return
        val target = File(root, name)
        if (record == null) {
            target.delete()
            return
        }
        val data = record.text.toByteArray(Charsets.UTF_8)
        if (data.size !in 1..limit) return
        val temp = File(root, ".pending-${UUID.randomUUID()}")
        try {
            if (!root.isDirectory && !root.mkdirs()) return
            FileOutputStream(temp).use { stream ->
                val output = DataOutputStream(stream.buffered())
                output.writeInt(FORMAT_VERSION)
                output.writeLong(record.expiresAt)
                output.writeInt(data.size)
                output.write(hash(record.expiresAt, data))
                output.write(data)
                output.flush()
                stream.fd.sync()
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            pruneDetails()
        } catch (_: IOException) {
            // The remote result remains usable when local storage is full or unavailable.
        } finally {
            temp.delete()
        }
    }

    @Synchronized
    fun clear() {
        generation++
        root.listFiles().orEmpty().filter { it.isFile && isCacheFile(it.name) }.forEach { file ->
            if (!file.delete() && file.exists()) throw IOException("无法清理在线曲库缓存")
        }
    }

    private fun pruneDetails() {
        root.listFiles().orEmpty().filter { it.isFile && DETAIL_FILE.matches(it.name) }
            .sortedByDescending(File::lastModified).drop(MAX_CACHED_DETAILS).forEach(File::delete)
    }

    private fun isCacheFile(name: String): Boolean =
        name == "catalog.cache" || DETAIL_FILE.matches(name) || PENDING_FILE.matches(name)

    private fun hash(expiresAt: Long, data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(expiresAt).array())
            digest(data)
        }

    private companion object {
        const val DIGEST_BYTES = 32
        const val HEADER_BYTES = Int.SIZE_BYTES + Long.SIZE_BYTES + Int.SIZE_BYTES + DIGEST_BYTES
        const val FORMAT_VERSION = 1
        const val MAX_CACHED_DETAILS = 24
        val DETAIL_FILE = Regex("score-[A-Za-z0-9_-]{6,32}\\.cache")
        val PENDING_FILE = Regex("\\.pending-[a-f0-9-]{36}")
    }
}
