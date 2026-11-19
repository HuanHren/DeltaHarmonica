package com.huanhren.deltaharmonica.midi

/** Keeps the PPQ divisor until the final millisecond conversion so tempo changes cannot accumulate rounding loss. */
internal class MidiTempoClock(ppq: Int, private val tempos: List<TempoPoint>) {
    private val millisecondsDivisor = ppq * 1_000L
    private val runningNumerators = LongArray(tempos.size)

    init {
        require(ppq > 0 && tempos.firstOrNull()?.tick == 0L)
        for (index in 1 until tempos.size) {
            val previous = tempos[index - 1]
            val segment = (tempos[index].tick - previous.tick) * previous.microsecondsPerQuarter
            runningNumerators[index] = runningNumerators[index - 1] + segment
        }
    }

    fun millisecondsAt(tick: Long): Long {
        var low = 0
        var high = tempos.lastIndex
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (tempos[middle].tick <= tick) low = middle else high = middle - 1
        }
        val tempo = tempos[low]
        return (runningNumerators[low] + (tick - tempo.tick) * tempo.microsecondsPerQuarter) / millisecondsDivisor
    }
}
