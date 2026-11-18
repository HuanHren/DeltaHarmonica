package com.huanhren.deltaharmonica.ui

import com.huanhren.deltaharmonica.data.*
import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.model.*
import com.huanhren.deltaharmonica.playback.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ImportPipelineTest {
    @get:Rule val temporary = TemporaryFolder()
    private val layout = DefaultLayouts.create(1).copy(referenceDisplay=ReferenceDisplay(2670,1200))
    private val oldScript = PerformanceScript(2,layout.profile!!.id,ScriptConverter.fingerprint(layout.profile!!,layout),"old.json",0,
        MonophonyPolicy.HIGHEST_NOTE,false,listOf(ScriptEvent(0,100,62,"NOTE_2",Region.NATURAL,false)),
        provenance=ScriptProvenance("CLEAN",emptyList(),0,0))
    private val midi get() = javaClass.getResourceAsStream("/duration16-test.mid")!!.readBytes()
    private suspend fun finish(coordinator: ImportCoordinator) = withTimeout(20_000) { coordinator.state.first { !it.active } }

    @Test fun sevenFailuresPreserveExistingWorkspaceAndAllowRetry() = runBlocking {
        for (stage in listOf("midi-parse","midi-convert","json-format","json-validation","read","save","activation")) {
            val root = temporary.newFolder(); val real = SongLibraryRepository(root)
            val old = real.add("old.json",SongSourceType.JSON,ScriptCodec.encode(oldScript).toByteArray(),oldScript,emptyList())
            val workspace = MidiWorkspace(); loadLibrarySong(real,old.id,workspace)
            val plan = PlaybackPlanner.plan(oldScript,layout,Region.NATURAL,false,0,1,1.0)
            assertTrue(plan.fatalErrors.toString(),plan.fatalErrors.isEmpty())
            workspace.dryRun=plan; workspace.speed=2.0; workspace.transpose=3
            val progress=PlaybackProgress(4,2,state="old",phase=PlaybackStatus.COMPLETED)
            val timing=ActualTiming(11,22,3,120,"old")
            PlaybackPreparation.progress.value=progress; PlaybackPreparation.actualTimings[0]=timing
            val ops = object : ImportFileOperations by RealImportFileOperations {
                override fun writeFile(file: File, bytes: ByteArray) {
                    if(stage == "save" && file.name == "metadata.json") throw IOException("save failed")
                    RealImportFileOperations.writeFile(file,bytes)
                }
            }
            val repo = SongLibraryRepository(root,ops); val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val coordinator=ImportCoordinator(scope)
            val pipeline=ImportPipeline(repo,workspace,coordinator,
                activate={ if(stage == "activation") throw IOException("activation failed") else loadLibrarySong(repo,it,workspace) },
                convert={ parsed,name,track,policy,transpose,profile,selectedLayout ->
                    if(stage == "midi-convert") throw IOException("conversion failed")
                    ScriptConverter.convert(parsed,name,track,policy,transpose,profile,selectedLayout)
                })
            var closed=false
            val json=ScriptCodec.encode(oldScript.copy(sourceMidiName="new.json"))
            val source = when(stage) {
                "midi-parse" -> "not midi".toByteArray()
                "midi-convert" -> midi
                "json-format" -> "{broken".toByteArray()
                "json-validation" -> json.replace("\"schemaVersion\": 2", "\"schemaVersion\": 99").toByteArray()
                else -> json.toByteArray()
            }
            try {
                assertTrue(pipeline.importDocument {
                    val input=object : java.io.ByteArrayInputStream(source) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if(stage == "read") throw IOException("read failed")
                            return super.read(b,off,len)
                        }
                        override fun close() { closed=true; super.close() }
                    }
                    "new" to DocumentTransfer.readBounded(input)
                })
                var state=finish(coordinator); assertTrue("$stage input not closed",closed)
                if(stage == "midi-convert") {
                    assertEquals(ImportPhase.READY,state.phase); assertNotNull(workspace.parsed)
                    assertTrue(pipeline.convertMidi(layout)); state=finish(coordinator)
                }
                assertEquals("$stage: ${state.message}",ImportPhase.FAILED,state.phase)
                assertEquals(stage == "activation",state.committed)
                assertEquals(old.id,workspace.songId); assertEquals("old.json",workspace.name); assertEquals(oldScript,workspace.report?.script)
                assertEquals(plan,workspace.dryRun); assertEquals(2.0,workspace.speed,0.0); assertEquals(3,workspace.transpose)
                assertEquals(progress,PlaybackPreparation.progress.value); assertEquals(timing,PlaybackPreparation.actualTimings[0])
                val reopened=SongLibraryRepository(root); reopened.refresh()
                assertEquals(if(stage == "activation") 2 else 1,reopened.items.value.size)
                assertEquals(oldScript,reopened.readScript(old.id)); assertEquals(old.contentHash,RealImportFileOperations.sha256(reopened.readOriginal(old.id)))
                val store=ImportJournalStore(root)
                if(stage == "activation") {
                    val id=requireNotNull(state.songId); val item=requireNotNull(reopened.get(id))
                    assertEquals(ImportJournalState.COMMITTED,store.ids().map(store::read).single { it.songId == id }.state)
                    assertEquals(item.contentHash,RealImportFileOperations.sha256(reopened.readOriginal(id)))
                    assertTrue(state.message.contains("曲目已保存")); loadLibrarySong(reopened,id,workspace); assertEquals(id,workspace.songId)
                }
                if(stage == "save") assertTrue(store.ids().map(store::read).any { it.state == ImportJournalState.ROLLED_BACK })
                assertTrue(pipeline.importDocument { "bad" to "{bad".toByteArray() }); assertEquals(ImportPhase.FAILED,finish(coordinator).phase)
            } finally { scope.cancel(); PlaybackPreparation.actualTimings.clear(); PlaybackPreparation.progress.value=PlaybackProgress() }
        }
    }

    @Test fun midiAndJsonBothUseProductionPathThroughCommitAndActivation() = runBlocking {
        for (format in listOf("midi", "json")) {
            val root=temporary.newFolder(); val repo=SongLibraryRepository(root); val workspace=MidiWorkspace()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val coordinator=ImportCoordinator(scope)
            val pipeline=ImportPipeline(repo,workspace,coordinator)
            try {
                val bytes=if(format == "midi") midi else ScriptCodec.encode(oldScript).toByteArray()
                assertTrue(pipeline.importDocument { format to DocumentTransfer.readBounded(bytes.inputStream()) })
                var state=finish(coordinator)
                if(format == "midi") {
                    assertEquals(ImportPhase.READY,state.phase); assertNotNull(workspace.parsed)
                    workspace.candidateTrack=workspace.parsed!!.tracks.indexOfFirst { it.notes.isNotEmpty() }
                    workspace.candidatePolicy=MonophonyPolicy.HIGHEST_NOTE
                    assertTrue(pipeline.convertMidi(layout)); state=finish(coordinator)
                }
                assertEquals("$format ${state.message}",ImportPhase.COMMITTED,state.phase)
                assertTrue(state.committed); assertEquals(state.songId,workspace.songId)
                val reopened=SongLibraryRepository(root); reopened.refresh(); assertEquals(1,reopened.items.value.size)
                assertEquals(state.songId,reopened.items.value.single().id)
                assertTrue(reopened.readScript(requireNotNull(state.songId)).events.isNotEmpty())
            } finally { scope.cancel(); PlaybackPreparation.actualTimings.clear(); PlaybackPreparation.progress.value=PlaybackProgress() }
        }
    }

    @Test fun rejectedRequestAndCancelledOldTaskCannotOverwriteImmediateRetry() = runBlocking {
        val root=temporary.newFolder(); val repo=SongLibraryRepository(root); val workspace=MidiWorkspace()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val coordinator=ImportCoordinator(scope); val pipeline=ImportPipeline(repo,workspace,coordinator)
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>(); val oldFinished=CompletableDeferred<Unit>()
        try {
            pipeline.importDocument {
                entered.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { release.await(); oldFinished.complete(Unit) } }
            }
            entered.await(); val oldId=coordinator.state.value.taskId
            assertFalse(pipeline.importDocument { error("rejected task executed") })
            assertNotNull(coordinator.state.value.rejection)
            coordinator.cancel()
            assertTrue(pipeline.importDocument { "new.json" to DocumentTransfer.readBounded(ScriptCodec.encode(oldScript).byteInputStream()) })
            val latest=finish(coordinator); assertEquals(ImportPhase.COMMITTED,latest.phase); assertNotEquals(oldId,latest.taskId)
            release.complete(Unit); oldFinished.await(); yield()
            assertEquals(latest.taskId,coordinator.state.value.taskId); assertEquals(latest.songId,workspace.songId)
            val reopened=SongLibraryRepository(root); reopened.refresh(); assertEquals(1,reopened.items.value.size)
        } finally { release.complete(Unit); scope.cancel(); PlaybackPreparation.actualTimings.clear(); PlaybackPreparation.progress.value=PlaybackProgress() }
    }

    @Test fun cancellingAfterCommitKeepsSongAndOldWorkspace() = runBlocking {
        val root=temporary.newFolder(); val repo=SongLibraryRepository(root)
        val first=repo.add("old.json",SongSourceType.JSON,ScriptCodec.encode(oldScript).toByteArray(),oldScript,emptyList())
        val workspace=MidiWorkspace(); loadLibrarySong(repo,first.id,workspace)
        val progress=PlaybackProgress(2,1,state="old",phase=PlaybackStatus.COMPLETED); PlaybackPreparation.progress.value=progress
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default); val coordinator=ImportCoordinator(scope); val entered=CompletableDeferred<Unit>()
        val pipeline=ImportPipeline(repo,workspace,coordinator,activate={ entered.complete(Unit); awaitCancellation() })
        try {
            val next=oldScript.copy(sourceMidiName="new.json")
            pipeline.importDocument { "new.json" to DocumentTransfer.readBounded(ScriptCodec.encode(next).byteInputStream()) }
            withTimeout(20_000) { entered.await() }; val id=requireNotNull(coordinator.state.value.songId)
            coordinator.cancel(); val result=finish(coordinator)
            assertEquals(ImportPhase.CANCELLED,result.phase); assertTrue(result.committed)
            assertEquals(first.id,workspace.songId); assertEquals(progress,PlaybackPreparation.progress.value)
            val reopened=SongLibraryRepository(root); reopened.refresh(); assertEquals(2,reopened.items.value.size)
            assertEquals(next,reopened.readScript(id))
            assertEquals(ImportJournalState.COMMITTED,ImportJournalStore(root).let { s -> s.ids().map(s::read).single { it.songId == id }.state })
        } finally { scope.cancel(); PlaybackPreparation.actualTimings.clear(); PlaybackPreparation.progress.value=PlaybackProgress() }
    }
}
