package com.huanhren.deltaharmonica.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootSequenceProtocolTest {
    private val note = RootSequenceStep(80, listOf(RootStroke(1f, 2f, 0, 45)))

    @Test fun readyMustIdentifyTheMatchingSessionAndVersion() {
        val sessionId = "2a1e96c6-c578-4f21-92ed-b4dcbbe13caa"
        assertTrue(RootSequenceProtocol.validSessionId(sessionId))
        assertFalse(RootSequenceProtocol.validSessionId("1-1-1-1-1"))
        assertEquals(RootHelperIdentity(73, 190_892), RootSequenceProtocol.decodeReady("READY 3 $sessionId 73 190892", sessionId))
        assertNull(RootSequenceProtocol.decodeReady("READY 2 $sessionId 73 190892", sessionId))
        assertNull(RootSequenceProtocol.decodeReady("READY 3 wrong-token 73 190892", sessionId))
        assertNull(RootSequenceProtocol.decodeReady("READY 3 $sessionId 1 190892", sessionId))
        assertNull(RootSequenceProtocol.decodeReady("READY 3 $sessionId 73 0", sessionId))
    }

    @Test fun processStartTicksSurviveSpacesAndParensInCommandName() {
        val middle = List(18) { "0" }.joinToString(" ")
        assertEquals(
            RootHelperIdentity(73, 190_892),
            RootSequenceProtocol.decodeProcessStat("73 (one ) two (three) S $middle 190892 0 0"),
        )
        assertNull(RootSequenceProtocol.decodeProcessStat("73 (truncated) S 0 0"))
    }

    @Test fun commitRequiresEveryIndexExactlyOnce() {
        val upload = RootSequenceUpload(2)
        assertFalse(upload.append(1, RootSequenceProtocol.encode(note)))
        assertTrue(upload.append(0, RootSequenceProtocol.encode(note)))
        assertNull(upload.commit())
        assertFalse(upload.append(0, RootSequenceProtocol.encode(note)))
        assertTrue(upload.append(1, RootSequenceProtocol.encode(note)))
        assertEquals(listOf(note, note), upload.commit())
        assertFalse(upload.append(2, RootSequenceProtocol.encode(note)))
    }

    @Test fun chunkIsAtomicAndRejectsOversizeCorruptAndUnsortedInput() {
        val upload = RootSequenceUpload(100)
        val same = RootSequenceProtocol.encode(note)
        assertFalse(upload.append(0, List(RootSequenceProtocol.CHUNK_SIZE + 1) { same }.joinToString("~")))
        assertFalse(upload.append(0, "$same~corrupt"))
        assertFalse(upload.append(0, "$same~${RootSequenceProtocol.encode(note.copy(noteAtMs = 70))}"))
        assertEquals(0, upload.received)
        assertTrue(upload.append(0, same))
        assertEquals(1, upload.received)
    }

    @Test fun sequenceCanUseWholeNoteDurationAfterControls() {
        val step = RootSequenceStep(100, listOf(
            RootStroke(0f, 1f, 0, 45),
            RootStroke(0f, 2f, 60, MAX_GESTURE_DURATION_MS)
        ))
        val input = RootSequenceProtocol.encode(step)
        assertTrue(RootSequenceUpload(1).append(0, input))
        assertNull(RootSequenceProtocol.decodeStrokes(input.substringAfter('|')))
        assertNotNull(RootSequenceProtocol.decodeStrokes(input.substringAfter('|'), sequence = true))
    }

    @Test fun acceptsOneHundredThousandNotesInBoundedChunks() {
        val upload = RootSequenceUpload(100_000)
        val encoded = RootSequenceProtocol.encode(note)
        val full = List(RootSequenceProtocol.CHUNK_SIZE) { encoded }.joinToString("~")
        var index = 0
        while (index + RootSequenceProtocol.CHUNK_SIZE <= 100_000) {
            assertTrue(upload.append(index, full))
            index += RootSequenceProtocol.CHUNK_SIZE
        }
        if (index < 100_000) assertTrue(upload.append(index, List(100_000 - index) { encoded }.joinToString("~")))
        assertEquals(100_000, upload.commit()?.size)
    }
}
