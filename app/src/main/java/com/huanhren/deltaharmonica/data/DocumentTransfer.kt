package com.huanhren.deltaharmonica.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.huanhren.deltaharmonica.midi.PerformanceScript
import com.huanhren.deltaharmonica.midi.ScriptCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext

object DocumentTransfer {
    suspend fun read(resolver: ContentResolver, uri: Uri): Pair<String, ByteArray> = withContext(Dispatchers.IO) {
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "midi.mid"
        val bytes = resolver.openInputStream(uri)?.let { readBounded(it) } ?: error("无法读取文件")
        name to bytes
    }

    internal suspend fun readBounded(stream: InputStream): ByteArray = stream.use { input ->
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 2_000_000) { "文件超过 2 MB" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    suspend fun writeScript(resolver: ContentResolver, uri: Uri, script: PerformanceScript) = withContext(Dispatchers.IO) {
        val encoded = ScriptCodec.encode(script)
        resolver.openOutputStream(uri, "wt")?.use { it.write(encoded.toByteArray(Charsets.UTF_8)) } ?: error("无法打开导出文件")
        val saved = resolver.openInputStream(uri)?.use { it.bufferedReader(Charsets.UTF_8).readText() } ?: error("无法重新读取导出文件")
        require(ScriptCodec.decode(saved) == script) { "导出内容校验失败" }
    }
}
