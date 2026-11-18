package com.huanhren.deltaharmonica.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream

class DocumentTransferFailureTest {
    @Test fun closeFailureIsNotReportedAsSuccessfulRead() = runBlocking {
        var closed = false
        val input = object : java.io.ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { closed = true; throw IOException("close failure") }
        }
        val failure = assertThrows(IOException::class.java) { runBlocking { DocumentTransfer.readBounded(input) } }
        assertEquals("close failure",failure.message); assertTrue(closed)
    }

    @Test fun readAndCloseFailureRetainsBothErrors() = runBlocking {
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("read failure")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("read failure")
            override fun close() { throw IOException("close failure") }
        }
        val failure = assertThrows(IOException::class.java) { runBlocking { DocumentTransfer.readBounded(input) } }
        assertEquals("read failure",failure.message)
        assertTrue(failure.suppressed.any { it.message == "close failure" })
    }

    @Test fun cancellationPropagatesAndClosesInput() = runBlocking {
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = throw CancellationException("cancelled read")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw CancellationException("cancelled read")
            override fun close() { closed = true }
        }
        assertThrows(CancellationException::class.java) { runBlocking { DocumentTransfer.readBounded(input) } }
        assertTrue(closed)
    }
}
