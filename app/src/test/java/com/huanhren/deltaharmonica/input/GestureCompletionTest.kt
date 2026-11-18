package com.huanhren.deltaharmonica.input

import org.junit.Assert.*
import org.junit.Test

class GestureCompletionTest {
    @Test fun duplicateCompletionIsAcceptedOnlyOnce() {
        val gate=GestureCompletion()
        assertTrue(gate.tryFinish())
        assertFalse(gate.tryFinish())
        assertFalse(gate.tryFinish())
    }
}
