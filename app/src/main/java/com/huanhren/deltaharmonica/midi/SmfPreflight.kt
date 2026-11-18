package com.huanhren.deltaharmonica.midi

internal data class PreflightResult(val bytes: ByteArray, val warnings: List<String>, val status: ImportStatus, val requiresConfirmation: Boolean, val diagnostics: List<ImportDiagnostic> = emptyList())
/** Bounded structural validation; recovery only occurs at a verified event boundary in the final truncated track. */
internal object SmfPreflight {
    private class Truncated(val offset: Int) : RuntimeException()
    fun validate(data: ByteArray): PreflightResult {
        val working = data.copyOf()
        val repairs = mutableListOf<ImportDiagnostic>()
        fun finish(bytes: ByteArray, warnings: List<String>, status: ImportStatus, consent: Boolean, diagnostics: List<ImportDiagnostic> = emptyList()): PreflightResult {
            if (repairs.isEmpty()) return PreflightResult(bytes, warnings, status, consent, diagnostics)
            val description = "已在内存副本中将 ${repairs.size} 个超范围音量控制值 FF 限制为 127；原文件未修改，请确认恢复结果。"
            return PreflightResult(bytes, warnings + description, ImportStatus.RECOVERED, true, diagnostics + repairs)
        }
        fun u(i: Int): Int { require(i in working.indices) { "MIDI 文件在偏移 $i 处截断" }; return working[i].toInt() and 255 }
        fun int32(i: Int): Long = (u(i).toLong() shl 24) or (u(i+1).toLong() shl 16) or (u(i+2).toLong() shl 8) or u(i+3).toLong()
        require(data.size >= 14 && int32(4) == 6L) { "MIDI 文件头无效或截断" }
        val count = (u(10) shl 8) or u(11); require(count in 1..128) { "轨道数量无效" }
        var pos = 14; var events = 0; var allTracksEnded = true
        repeat(count) { ti ->
            require(pos.toLong()+8 <= data.size && u(pos) == 77 && u(pos+1) == 84 && u(pos+2) == 114 && u(pos+3) == 107) { "轨道 ${ti+1} 的头在偏移 $pos 处无效或截断" }
            val header = pos; val length = int32(pos+4); val start = pos+8
            val declaredEnd = start.toLong()+length
            val truncated = declaredEnd > data.size
            require(!truncated || ti == count-1) { "轨道 ${ti+1} 的声明长度超出文件，后续轨道无法定位" }
            val end = minOf(declaredEnd, data.size.toLong()).toInt()
            pos = start; var running = 0; var ended = false; var completeCount = 0; var lastGood = start
            fun read(): Int { if (pos >= end) throw Truncated(pos); return u(pos++) }
            fun vlq(): Int {
                var n = 0
                repeat(4) { val b=read(); n=(n shl 7) or (b and 127); if (b < 128) return n }
                throw IllegalArgumentException("轨道 ${ti+1} 在偏移 $pos 处的 MIDI 变长整数无效")
            }
            try {
                while (pos < end) {
                    require(++events <= 200_000) { "MIDI 事件超过限制" }
                    vlq()
                    if (pos >= end) throw Truncated(pos)
                    val peek = u(pos)
                    val status = if (peek >= 128) read() else running
                    require(status != 0) { "轨道 ${ti+1} 在偏移 $pos 处的 running status 无效" }
                    if (status == 255 || status == 240 || status == 247) {
                        running = 0
                        val metaType = if (status == 255) read() else -1
                        val payloadLength = vlq()
                        if (payloadLength > end-pos) {
                            if (truncated) throw Truncated(pos)
                            throw IllegalArgumentException("轨道 ${ti+1} 在偏移 $pos 处的事件长度超出轨道")
                        }
                        pos += payloadLength
                        if (metaType == 0x2f) { require(payloadLength == 0 && pos == end) { "轨道 ${ti+1} 的结束事件或声明长度无效" }; ended = true }
                    } else {
                        require(status in 128..239) { "轨道 ${ti+1} 在偏移 $pos 处存在不支持的 MIDI 状态" }
                        running = status
                        val size = if (status and 0xf0 == 0xc0 || status and 0xf0 == 0xd0) 1 else 2
                        var controller = -1
                        repeat(size) { index ->
                            val value = read()
                            if (index == 0) controller = value
                            if (value >= 128) {
                                if (status and 0xf0 == 0xb0 && controller == 7 && index == 1 && value == 255) {
                                    working[pos-1] = 127
                                    repairs.add(ImportDiagnostic("INVALID_VOLUME", pos-1, ti, 1, null))
                                } else throw IllegalArgumentException("轨道 ${ti+1} 在偏移 ${pos-1} 处的数据字节无效")
                            }
                        }
                    }
                    completeCount++; lastGood = pos
                }
            } catch (e: Truncated) {
                if (!truncated || completeCount == 0) throw IllegalArgumentException("轨道 ${ti+1} 在偏移 ${e.offset} 处截断；无法安全恢复")
            }
            if (truncated) {
                require(completeCount > 0 && lastGood > start) { "轨道 ${ti+1} 在偏移 $start 处截断，没有完整事件可恢复" }
                val addedEnd = if (ended) 0 else 4
                val restored = working.copyOf(lastGood + addedEnd)
                val restoredLen = lastGood - start + addedEnd
                for (i in 0..3) restored[header+4+i] = (restoredLen ushr (24-i*8)).toByte()
                if (!ended) { restored[lastGood] = 0; restored[lastGood+1] = 0xff.toByte(); restored[lastGood+2] = 0x2f; restored[lastGood+3] = 0 }
                val incomplete = if (data.size > lastGood) 1 else 0
                val diagnostic = ImportDiagnostic("TRUNCATED_TRACK", lastGood, ti, incomplete, null)
                val previousWarnings = if (allTracksEnded) emptyList() else listOf("此前部分轨道缺少结束事件，可能缺少后续音符。")
                val reason = if (ended) "轨道 ${ti+1} 的声明长度超出文件，但已读取完整结束事件；按实际边界恢复，仍需确认。" else "轨道 ${ti+1} 在文件偏移 ${data.size} 处截断，恢复到偏移 $lastGood；保留前 $completeCount 个完整事件，未完成事件 $incomplete 个（${data.size-lastGood} 字节）；后续可能丢失的音符数量未知。"
                return finish(restored, previousWarnings + reason, ImportStatus.RECOVERED, true, listOf(diagnostic))
            }
            allTracksEnded = allTracksEnded && ended
        }
        if (pos < data.size) {
            require(allTracksEnded) { "轨道未完整结束，不能忽略轨道外数据" }
            val extra = data.size-pos
            val padding = (pos until data.size).all { data[it] == 0.toByte() }
            val msg = if (padding) "所有轨道完整；文件末尾有 $extra 字节零填充。" else "文件包含 $extra 字节轨道外数据，已忽略；这些数据可能包含未解析的音符。"
            return finish(working.copyOf(pos), listOf(msg), ImportStatus.WARNING, !padding, listOf(ImportDiagnostic(if (padding) "ZERO_PADDING" else "TRAILING_DATA", pos)))
        }
        if (!allTracksEnded) return finish(working, listOf("部分轨道缺少结束事件，可能缺少后续音符。"), ImportStatus.WARNING, true)
        return finish(working, emptyList(), ImportStatus.CLEAN, false)
    }
}
