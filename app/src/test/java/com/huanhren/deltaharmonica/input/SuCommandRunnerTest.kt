package com.huanhren.deltaharmonica.input

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class SuCommandRunnerTest {
    @Test fun slowPipeCannotExtendTheCommandDeadline() = kotlinx.coroutines.runBlocking {
        val process = fakeProcess(object : InputStream() {
            override fun read(): Int {
                Thread.sleep(800)
                return -1
            }
        })
        val elapsed = measureTimeMillis {
            assertEquals(RootCommandResult.TimedOut, SuCommandRunner { process }.run("id -u", 75))
        }
        assertTrue("returned after ${elapsed}ms", elapsed < 650)
    }

    @Test fun outputReadFailureIsReturnedInsteadOfCrashing() = runTest {
        val process = fakeProcess(object : InputStream() {
            override fun read(): Int = throw IOException("read failed")
            override fun close() { throw IOException("close failed") }
        })
        val result = SuCommandRunner { process }.run("id -u", 1000)
        assertTrue(result is RootCommandResult.Failure)
    }

    @Test fun blockedProcessTeardownDoesNotExtendTheProbeDeadline() = kotlinx.coroutines.runBlocking {
        val destroyStarted = CountDownLatch(1)
        val releaseDestroy = CountDownLatch(1)
        val process = fakeProcess(object : InputStream() {
            override fun read(): Int {
                releaseDestroy.await(2, TimeUnit.SECONDS)
                return -1
            }
        }, onDestroy = {
            destroyStarted.countDown()
            releaseDestroy.await(2, TimeUnit.SECONDS)
        })
        try {
            val elapsed = measureTimeMillis {
                assertEquals(RootCommandResult.TimedOut, SuCommandRunner { process }.run("id -u", 75))
            }
            assertTrue("returned after ${elapsed}ms", elapsed < 1_000)
            assertTrue(destroyStarted.await(1, TimeUnit.SECONDS))
        } finally {
            releaseDestroy.countDown()
        }
    }

    private fun fakeProcess(input: InputStream, onDestroy: () -> Unit = {}) = object : Process() {
            override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
            override fun getInputStream(): InputStream = input
            override fun getErrorStream(): InputStream = InputStream.nullInputStream()
            override fun waitFor() = 0
            override fun waitFor(timeout: Long, unit: TimeUnit) = true
            override fun exitValue() = 0
            override fun destroy() = Unit
            override fun destroyForcibly(): Process {
                onDestroy()
                return this
            }
    }
}
