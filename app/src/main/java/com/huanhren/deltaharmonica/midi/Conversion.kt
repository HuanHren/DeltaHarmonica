package com.huanhren.deltaharmonica.midi

import com.huanhren.deltaharmonica.model.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.PriorityQueue

@Serializable enum class MonophonyPolicy { HIGHEST_NOTE, LOWEST_NOTE, LONGEST_NOTE, TRACK_SELECTION }
data class MidiNote(val startMs: Long, val durationMs: Long, val pitch: Int, val velocity: Int, val track: Int) {
    init { require(startMs >= 0 && durationMs > 0 && startMs <= Long.MAX_VALUE - durationMs && pitch in 0..127 && velocity in 1..127 && track >= 0) }
}
data class MonoResult(
    val notes: List<MidiNote>,
    val discardedMs: Long,
    val retainedSourceNoteCount: Int,
    val overlapClippedNoteCount: Int,
    val overlapDroppedNoteCount: Int,
)
object Monophony {
    fun convert(input: List<MidiNote>, policy: MonophonyPolicy, checkActive: () -> Unit = {}): MonoResult {
        require(input.size <= 100_000) { "音符数量超过限制" }
        data class Edge(val time: Long, val begin: Boolean, val id: Int)
        val edges = ArrayList<Edge>(input.size * 2)
        input.forEachIndexed { index, note ->
            if (index % 1024 == 0) checkActive()
            edges.add(Edge(note.startMs, true, index))
            edges.add(Edge(note.startMs + note.durationMs, false, index))
        }
        edges.sortWith(compareBy<Edge> { it.time }.thenBy { it.begin }.thenBy { it.id })
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
        val retainedMs = LongArray(input.size)
        val queue = PriorityQueue(comparator)
        val output = mutableListOf<Pair<Int,MidiNote>>()
        var previous: Long? = null
        for ((index, edge) in edges.withIndex()) {
            if (index % 1024 == 0) checkActive()
            val last = previous
            if (last != null && edge.time > last) {
                while (queue.isNotEmpty() && !active[queue.peek()!!]) queue.poll()
                if (queue.isNotEmpty()) {
                    val id = queue.peek()!!; val n = input[id]; val end = edge.time
                    retainedMs[id] += end - last
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
        var discardedMs = 0L
        var clipped = 0
        var dropped = 0
        input.forEachIndexed { index, note ->
            if (index % 1024 == 0) checkActive()
            val retained = retainedMs[index]
            discardedMs = Math.addExact(discardedMs, note.durationMs - retained)
            if (retained == 0L) dropped++
            else if (retained < note.durationMs) clipped++
        }
        return MonoResult(output.map { it.second }, discardedMs, input.size - dropped, clipped, dropped)
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

    /**
     * Finds exact-pitch fingerings. Playback can supply the estimated physical delay left after
     * accounting for the space around each pair of notes. The first candidate has no predecessor.
     * Minimize total estimated delay, then control changes, with a stable region/semitone/key order.
     */
    fun optimize(profile: InstrumentProfile, pitches: List<Int>, initialRegion: Region? = null,
                 initialSemitone: Boolean? = null, allowedKeys: Set<Int>? = null,
                 checkActive: () -> Unit = {},
                 transitionDelayMs: ((previousIndex: Int?, index: Int, previous: PitchChoice?, next: PitchChoice) -> Long)? = null,
    ): List<PitchChoice?> {
        require(pitches.size <= 200_000)
        val order = compareBy<PitchChoice> { it.region.ordinal }.thenBy { it.semitone }.thenBy { it.keyIndex }
        val byPitch = Array(128) { pitch ->
            val choices = candidates(profile, pitch, allowedKeys).sortedWith(order)
            if (transitionDelayMs == null) choices.distinctBy { it.region to it.semitone } else choices
        }
        val width = byPitch.maxOf { it.size }
        val result = MutableList<PitchChoice?>(pitches.size) { null }
        if (width == 0) return result
        val predecessors = arrayOfNulls<IntArray>(pitches.size)
        var previousIndex: Int? = null
        var previousOptions = emptyList<PitchChoice>()
        var delays = LongArray(width)
        var nextDelays = LongArray(width)
        var changes = IntArray(width)
        var nextChanges = IntArray(width)
        for ((index, pitch) in pitches.withIndex()) {
            if (index % 1024 == 0) checkActive()
            val options = byPitch.getOrNull(pitch).orEmpty()
            if (options.isEmpty()) continue
            val back = IntArray(options.size)
            for ((choiceIndex, choice) in options.withIndex()) {
                if (previousIndex == null) {
                    nextDelays[choiceIndex] = transitionDelayMs?.invoke(null, index, null, choice) ?: 0
                    require(nextDelays[choiceIndex] >= 0)
                    nextChanges[choiceIndex] = controlChanges(initialRegion, initialSemitone, choice)
                    continue
                }
                var bestDelay = Long.MAX_VALUE
                var bestChanges = Int.MAX_VALUE
                for ((priorIndex, prior) in previousOptions.withIndex()) {
                    val transition = transitionDelayMs?.invoke(previousIndex, index, prior, choice) ?: 0
                    require(transition >= 0)
                    val delay = Math.addExact(delays[priorIndex], transition)
                    val controlChanges = changes[priorIndex] + controlChanges(prior.region, prior.semitone, choice)
                    if (delay < bestDelay || (delay == bestDelay && controlChanges < bestChanges)) {
                        bestDelay = delay
                        bestChanges = controlChanges
                        back[choiceIndex] = priorIndex
                    }
                }
                nextDelays[choiceIndex] = bestDelay
                nextChanges[choiceIndex] = bestChanges
            }
            predecessors[index] = back
            previousIndex = index
            previousOptions = options
            val priorDelays = delays
            delays = nextDelays
            nextDelays = priorDelays
            val priorChanges = changes
            changes = nextChanges
            nextChanges = priorChanges
        }
        var choiceIndex = previousOptions.indices.minWithOrNull(compareBy<Int> { delays[it] }.thenBy { changes[it] })
            ?: return result
        for (index in pitches.indices.reversed()) {
            if (index % 1024 == 0) checkActive()
            val back = predecessors[index] ?: continue
            result[index] = byPitch[pitches[index]][choiceIndex]
            choiceIndex = back[choiceIndex]
        }
        return result
    }

    private fun controlChanges(previousRegion: Region?, previousSemitone: Boolean?, next: PitchChoice) =
        (if (previousRegion != null && previousRegion != next.region) 1 else 0) +
            (if (previousSemitone != null && previousSemitone != next.semitone) 1 else 0)
}
@Serializable data class ScriptTempo(val startTimeMs: Long, val microsecondsPerQuarter: Int)
@Serializable data class ScriptTimeSignature(val startTimeMs: Long, val numerator: Int, val denominator: Int)
/** [midiNote] is the performed pitch. Subtract the script's transpose for the source pitch. */
@Serializable data class ScriptEvent(val startTimeMs: Long, val durationMs: Long, val midiNote: Int, val noteKeyId: String, val pitchRegion: Region, val semitoneEnabled: Boolean)
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class ScriptProvenance(
    val importStatus: String,
    val warnings: List<String>,
    val discardedMs: Long,
    val unmappedCount: Int,
    val sourceNoteCount: Int? = null,
    val monophonicCount: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val parsedNoteCount: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val retainedSourceNoteCount: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val overlapClippedNoteCount: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val overlapDroppedNoteCount: Int? = null,
)
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
        script.provenance?.let { provenance ->
            require(provenance.importStatus in setOf("CLEAN", "WARNING", "RECOVERED"))
            require(provenance.discardedMs >= 0 && provenance.unmappedCount >= 0)
            require(provenance.sourceNoteCount == null || provenance.sourceNoteCount in 0..200_000)
            require(provenance.monophonicCount == null || provenance.monophonicCount in 0..200_000)
            require(provenance.warnings.size <= 100_100 && provenance.warnings.all { it.length <= 1000 })
            validateCounts(provenance, script.events.size)
        }
        var end = 0L
        script.events.forEach { e ->
            require(e.startTimeMs >= end && e.durationMs > 0 && e.startTimeMs <= Long.MAX_VALUE - e.durationMs && e.midiNote in 0..127 && e.noteKeyId.isNotBlank())
            end = e.startTimeMs + e.durationMs
        }
    }

    private fun validateCounts(provenance: ScriptProvenance, mappedCount: Int) {
        val parsed = provenance.parsedNoteCount
        val retained = provenance.retainedSourceNoteCount
        val clipped = provenance.overlapClippedNoteCount
        val dropped = provenance.overlapDroppedNoteCount
        if (parsed == null) {
            require(retained == null && clipped == null && dropped == null)
            return
        }
        require(parsed in 0..100_000 && parsed <= (provenance.sourceNoteCount ?: parsed))
        require(retained != null && retained in 0..parsed)
        require(clipped != null && clipped in 0..retained)
        require(dropped == parsed - retained)
        val fragments = requireNotNull(provenance.monophonicCount)
        require(fragments >= retained && provenance.unmappedCount <= fragments)
        require(mappedCount == fragments - provenance.unmappedCount)
    }
}



