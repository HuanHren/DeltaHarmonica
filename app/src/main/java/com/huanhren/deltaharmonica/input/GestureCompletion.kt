package com.huanhren.deltaharmonica.input

import java.util.concurrent.atomic.AtomicBoolean

/** Ensures completion, cancellation, rejection and late duplicate callbacks settle only once. */
class GestureCompletion {
    private val finished=AtomicBoolean(false)
    fun tryFinish(): Boolean = finished.compareAndSet(false,true)
}
