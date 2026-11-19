package com.huanhren.deltaharmonica.data

import com.huanhren.deltaharmonica.midi.MonophonyPolicy
import com.huanhren.deltaharmonica.midi.PerformanceScript
import com.huanhren.deltaharmonica.midi.ScriptEvent
import com.huanhren.deltaharmonica.model.Region
import com.huanhren.deltaharmonica.model.DefaultLayouts
import com.huanhren.deltaharmonica.ui.MidiWorkspace
import com.huanhren.deltaharmonica.ui.OnlineConversionOptions
import com.huanhren.deltaharmonica.ui.loadLibrarySong
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OnlineSongPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    // Synthetic single note, 480 PPQ, 120 BPM, pitch 60 for one beat.
    private val original = Base64.getUrlDecoder().decode("c3HzCGZ8wMzAyPAglpGBhQXEYLYBAA")
    private val script = PerformanceScript(1, "profile", "fingerprint", "song.dfhs", 0,
        MonophonyPolicy.HIGHEST_NOTE, false, listOf(ScriptEvent(0, 500, 60, "NOTE_1", Region.NATURAL, false)))
    private val source = OnlineSongOrigin("public1", 42)

    @Test fun roundTripsAndActivatesWithoutAnyNetworkDependency() = runBlocking {
        val root = temporary.newFolder()
        val item = SongLibraryRepository(root).addOnline("歌曲", original, script, listOf("warning"), source)
        val reopened = SongLibraryRepository(root)
        reopened.refresh()
        assertEquals(item, reopened.findOnline(source.remoteId))
        assertEquals(SongSourceType.ONLINE_DFHS, item.sourceType)
        assertEquals("歌曲.dfhs", item.originalFileName)
        assertEquals(RealImportFileOperations.sha256(original), item.contentHash)
        assertArrayEquals(original, reopened.readOriginal(item.id))
        assertEquals(script, reopened.readScript(item.id))
        val workspace = MidiWorkspace()
        loadLibrarySong(reopened, item.id, workspace)
        assertEquals(item.id, workspace.songId)
        assertEquals(script, workspace.report?.script)
        assertEquals(listOf("warning"), workspace.report?.warnings)
    }

    @Test fun deduplicatesConcurrentWritesAndPersistsAnAliasWithoutChangingLocalName() = runBlocking {
        val root = temporary.newFolder()
        val library = SongLibraryRepository(root)
        val first = async { library.addOnline("歌曲", original, script, emptyList(), source) }
        val second = async { SongLibraryRepository(root).addOnline("歌曲", original, script, emptyList(), source) }
        val item = first.await()
        assertEquals(item.id, second.await().id)
        library.rename(item.id, "我的歌曲")
        val alias = source.copy(remoteId = "public2", revisions = mapOf("public2" to 45L), updatedAt = 45)
        assertEquals(item.id, library.addOnline("另一标题", original, script, emptyList(), alias).id)
        val reopened = SongLibraryRepository(root)
        reopened.refresh()
        assertEquals(1, reopened.items.value.size)
        assertEquals("我的歌曲", reopened.findOnline("public2")?.displayName)
        assertEquals(45L, reopened.findOnline("public2")?.online?.revision("public2"))
    }

    @Test fun anExplicitRevisionKeepsTheRenamedOriginalAndDamagedFilesCanBeReplaced() = runBlocking {
        val root = temporary.newFolder()
        val library = SongLibraryRepository(root)
        val first = library.addOnline("歌曲", original, script, emptyList(), source)
        library.rename(first.id, "我改过的曲名")
        val replacement = original + 0.toByte()
        val revision = OnlineSongOrigin("public1", 50)
        val updated = library.addOnline("歌曲", replacement, script, emptyList(), revision, allowNewRevision = true)
        assertNotEquals(first.id, updated.id)
        assertEquals("我改过的曲名", library.get(first.id)?.displayName)
        assertEquals(updated.id, library.findOnline("public1")?.id)
        root.resolve(updated.id).resolve("original").writeText("damaged")
        library.refresh()
        assertTrue(library.problems.value.containsKey(updated.id))
        assertEquals(first.id, library.findOnline("public1")?.id)
        val recovered = library.addOnline("歌曲", replacement, script, emptyList(), revision, allowNewRevision = true)
        assertNotEquals(updated.id, recovered.id)
        assertArrayEquals(replacement, library.readOriginal(recovered.id))
    }

    @Test fun writeFailureLeavesNoPartialSong() = runBlocking {
        val root = temporary.newFolder()
        val operations = object : ImportFileOperations by RealImportFileOperations {
            override fun openScriptOutput(file: File): java.io.OutputStream = throw IOException("script write failure")
        }
        assertThrows(IOException::class.java) {
            runBlocking {
                SongLibraryRepository(root, operations).addOnline("歌曲", original, script, emptyList(), source)
            }
        }
        val reopened = SongLibraryRepository(root)
        reopened.refresh()
        assertNull(reopened.findOnline(source.remoteId))
        assertTrue(reopened.items.value.isEmpty())
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".pending-") })
    }

    @Test fun cancelledAfterPublicationStillUpdatesTheLocalLibrary() = runBlocking {
        val root = temporary.newFolder()
        val published = CountDownLatch(1)
        val release = CountDownLatch(1)
        val operations = object : ImportFileOperations by RealImportFileOperations {
            override fun publishDirectory(source: File, target: File) {
                RealImportFileOperations.publishDirectory(source, target)
                published.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }
        val library = SongLibraryRepository(root, operations)
        val task = launch { library.addOnline("歌曲", original, script, emptyList(), source) }
        try {
            assertTrue(withContext(Dispatchers.IO) { published.await(10, TimeUnit.SECONDS) })
            task.cancel()
        } finally {
            release.countDown()
        }
        task.join()
        val item = library.items.value.single()
        assertEquals(source.remoteId, item.online?.remoteId)
        assertEquals(script, library.readScript(item.id))
    }

    @Test fun malformedMetadataIsReportedAndAValidDownloadCanBeSaved() = runBlocking {
        val root = temporary.newFolder()
        val library = SongLibraryRepository(root)
        val first = library.addOnline("歌曲", original, script, emptyList(), source)
        root.resolve(first.id).resolve("metadata.json").writeText("{invalid")
        library.refresh()
        assertTrue(library.problems.value.containsKey(first.id))
        assertNull(library.findOnline(source.remoteId))
        val saved = library.addOnline("歌曲", original, script, emptyList(), source)
        assertNotEquals(first.id, saved.id)
        assertArrayEquals(original, library.readOriginal(saved.id))
    }

    @Test fun adjustsTheCurrentPlaybackFromTheOfflineOriginalWithoutReplacingItsScript() = runBlocking {
        val root = temporary.newFolder()
        val library = SongLibraryRepository(root)
        val item = library.addOnline("歌曲", original, script, emptyList(), source)
        val workspace = MidiWorkspace()
        val options = OnlineConversionOptions(MonophonyPolicy.LOWEST_NOTE, 1)
        loadLibrarySong(library, item.id, workspace, DefaultLayouts.create(), options)
        val current = requireNotNull(workspace.report).script
        assertEquals(61, current.events.single().midiNote)
        assertEquals(1, current.transpose)
        assertEquals(MonophonyPolicy.LOWEST_NOTE, current.monophonyPolicy)
        assertEquals(script, library.readScript(item.id))
        assertEquals(source, library.get(item.id)?.online)
    }
}
