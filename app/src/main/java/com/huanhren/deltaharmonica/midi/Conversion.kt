package com.huanhren.deltaharmonica.midi

import com.huanhren.deltaharmonica.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.PriorityQueue

@Serializable enum class MonophonyPolicy { HIGHEST_NOTE, LOWEST_NOTE, LONGEST_NOTE, TRACK_SELECTION }
data class MidiNote(val startMs: Long, val durationMs: Long, val pitch: Int, val velocity: Int, val track: Int) {
    init { require(startMs >= 0 && durationMs > 0 && startMs <= Long.MAX_VALUE - durationMs && pitch in 0..127 && velocity in 1..127 && track >= 0) }
}
data class MonoResult(val notes: List<MidiNote>, val discardedMs: Long)
object Monophony {
    fun convert(input: List<MidiNote>, policy: MonophonyPolicy): MonoResult {
        require(input.size <= 100_000) { "音符数量超过限制" }
        data class Edge(val time: Long, val begin: Boolean, val id: Int)
        val edges = input.flatMapIndexed { i, n -> listOf(Edge(n.startMs, true, i), Edge(n.startMs+n.durationMs, false, i)) }
            .sortedWith(compareBy<Edge> { it.time }.thenBy { it.begin }.thenBy { it.id })
        val comparator = Comparator<Int> { a,b ->
            val x = input[a]; val y = input[b]
            val v = when(policy) {
                MonophonyPolicy.HIGHEST_NOTE, MonophonyPolicy.TRACK_SELECTION -> y.pitch.compareTo(x.pitch)
                MonophonyPolicy.LOWEST_NOTE -> x.pitch.compareTo(y.pitch)
                MonophonyPolicy.LONGEST_NOTE -> y.durationMs.compareTo(x.durationMs)
            }
            if (v != 0) v else compareValuesBy(a,b, { input[it].startMs }, { input[it].track }, { it })
        }
        val active = BooleanArray(input.size)
        val queue = PriorityQueue(comparator)
        val output = mutableListOf<Pair<Int,MidiNote>>()
        var previous: Long? = null
        for (edge in edges) {
            val last = previous
            if (last != null && edge.time > last) {
                while (queue.isNotEmpty() && !active[queue.peek()!!]) queue.poll()
                if (queue.isNotEmpty()) {
                    val id = queue.peek()!!; val n = input[id]; val end = edge.time
                    val prev = output.lastOrNull()
                    if (prev != null && prev.first == id && prev.second.startMs + prev.second.durationMs == last)
                        output[output.lastIndex] = id to prev.second.copy(durationMs = end - prev.second.startMs)
                    else output.add(id to n.copy(startMs = last, durationMs = end - last))
                }
            }
            active[edge.id] = edge.begin
            if (edge.begin) queue.add(edge.id)
            previous = edge.time
        }
        return MonoResult(output.map { it.second }, input.sumOf { it.durationMs } - output.sumOf { it.second.durationMs })
    }
}
data class PitchChoice(val region: Region, val semitone: Boolean, val keyIndex: Int)
object PitchMapper {
    fun candidates(profile: InstrumentProfile, pitch: Int, allowedKeys: Set<Int>? = null): List<PitchChoice> =
        Region.entries.flatMap { region -> profile.pitches[region].orEmpty().flatMapIndexed { index, base ->
            if (allowedKeys != null && index !in allowedKeys) emptyList() else
                listOf(false,true).filter { base + (if (it) 1 else 0) == pitch }.map { PitchChoice(region,it,index) }
        } }
    fun map(profile: InstrumentProfile, pitch: Int, previousRegion: Region?, previousSemitone: Boolean?): PitchChoice? =
        candidates(profile,pitch).minWithOrNull(compareBy<PitchChoice> { (if (previousRegion != null && it.region != previousRegion) 1 else 0) + (if (previousSemitone != null && it.semitone != previousSemitone) 1 else 0) }
            .thenBy { it.semitone }.thenBy { it.region.ordinal }.thenBy { it.keyIndex })

    /** Dynamic programming over six region/semitone states; unavailable pitches remain null. */
    fun optimize(profile: InstrumentProfile, pitches: List<Int>, initialRegion: Region? = null,
                 initialSemitone: Boolean? = null, allowedKeys: Set<Int>? = null): List<PitchChoice?> {
        require(pitches.size <= 200_000)
        val byPitch=(0..127).map { pitch ->
            val states=arrayOfNulls<PitchChoice>(6)
            candidates(profile,pitch,allowedKeys).forEach { choice ->
                val state=choice.region.ordinal*2 + if(choice.semitone) 1 else 0
                if (states[state] == null || choice.keyIndex < states[state]!!.keyIndex) states[state]=choice
            }
            states
        }
        val valid=pitches.indices.filter { pitches[it] in 0..127 && byPitch[pitches[it]].any { c -> c != null } }
        val result=MutableList<PitchChoice?>(pitches.size) { null }
        if (valid.isEmpty()) return result
        val back=Array(valid.size) { IntArray(6) { -1 } }
        val inf=Int.MAX_VALUE/4
        var costs=IntArray(6) { inf }
        valid.forEachIndexed { row,source ->
            val options=byPitch[pitches[source]]; val next=IntArray(6) { inf }
            for(state in 0 until 6) {
                val choice=options[state] ?: continue
                if(row==0) {
                    next[state]=(if(initialRegion != null && initialRegion != choice.region) 1 else 0) + (if(initialSemitone != null && initialSemitone != choice.semitone) 1 else 0)
                } else for(prev in 0 until 6) {
                    if(costs[prev] == inf) continue
                    val cost=costs[prev]+(if(prev/2 != state/2) 1 else 0)+(if(prev%2 != state%2) 1 else 0)
                    if(cost < next[state]) { next[state]=cost; back[row][state]=prev }
                }
            }
            costs=next
        }
        var state=(0 until 6).minBy { costs[it] }
        for(row in valid.lastIndex downTo 0) {
            val source=valid[row]; result[source]=byPitch[pitches[source]][state]
            if(row>0) state=back[row][state]
        }
        return result
    }
}
@Serializable data class ScriptTempo(val startTimeMs: Long, val microsecondsPerQuarter: Int)
@Serializable data class ScriptTimeSignature(val startTimeMs: Long, val numerator: Int, val denominator: Int)
@Serializable data class ScriptEvent(val startTimeMs: Long, val durationMs: Long, val midiNote: Int, val noteKeyId: String, val pitchRegion: Region, val semitoneEnabled: Boolean)
@Serializable data class ScriptProvenance(val importStatus: String, val warnings: List<String>, val discardedMs: Long, val unmappedCount: Int,
    val sourceNoteCount: Int? = null, val monophonicCount: Int? = null)
@Serializable data class PerformanceScript(val schemaVersion: Int = 1, val instrumentProfileId: String, val profileFingerprint: String,
    val sourceMidiName: String, val sourceTrack: Int, val monophonyPolicy: MonophonyPolicy, val mappingVerified: Boolean,
    val events: List<ScriptEvent>, val tempos: List<ScriptTempo> = emptyList(), val transpose: Int = 0, val ppq: Int = 1, val timeSignatures: List<ScriptTimeSignature> = emptyList(), val provenance: ScriptProvenance? = null)
object ScriptCodec {
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    fun encode(script: PerformanceScript): String { validate(script); return json.encodeToString(script) }
    fun decode(value: String): PerformanceScript = json.decodeFromString<PerformanceScript>(value).also(::validate)
    fun decodeImport(value: String): PerformanceScript = try { decode(value) }
        catch (e: IllegalArgumentException) { throw IllegalArgumentException("JSON 脚本无效或版本不受支持", e) }
    fun validate(script: PerformanceScript) {
        require(script.schemaVersion in 1..2 && (script.schemaVersion != 2 || script.provenance != null) && script.instrumentProfileId.isNotBlank() && script.profileFingerprint.isNotBlank() && script.sourceMidiName.isNotBlank() && script.sourceTrack >= 0)
        require(script.events.size <= 100_000) { "演奏事件超过 10 万，无法保存到曲库" }
        require(script.transpose in -72..72 && script.ppq in 1..32767 && script.timeSignatures.all { it.startTimeMs >= 0 && it.numerator > 0 && it.denominator in 1..128 } && script.tempos.all { it.startTimeMs >= 0 && it.microsecondsPerQuarter > 0 } && script.tempos.zipWithNext().all { (a,b) -> a.startTimeMs <= b.startTimeMs })
        script.provenance?.let { require(it.importStatus in setOf("CLEAN", "WARNING", "RECOVERED") && it.discardedMs >= 0 && it.unmappedCount >= 0 && (it.sourceNoteCount == null || it.sourceNoteCount in 0..100_000) && (it.monophonicCount == null || it.monophonicCount in 0..200_000) && it.warnings.size <= 100_100 && it.warnings.all { warning -> warning.length <= 1000 }) }
        var end = 0L
        script.events.forEach { e ->
            require(e.startTimeMs >= end && e.durationMs > 0 && e.startTimeMs <= Long.MAX_VALUE - e.durationMs && e.midiNote in 0..127 && e.noteKeyId.isNotBlank())
            end = e.startTimeMs + e.durationMs
        }
    }
}



