package com.huanhren.deltaharmonica

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.huanhren.deltaharmonica.data.LayoutRepository
import com.huanhren.deltaharmonica.data.SongLibraryRepository
import com.huanhren.deltaharmonica.input.RootCommandResult
import com.huanhren.deltaharmonica.input.RootCommandRunner
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** The instrumentation host has no global gesture services or privileged command runner. */
class IsolatedTestApplication : DeltaHarmonicaApp() {
    override val rootUiAllowed = false
    lateinit var runDirectory: File
        private set

    override fun onCreate() {
        super.onCreate()
        check(packageName == "com.huanhren.deltaharmonica.instrumented")
        val data = File(applicationInfo.dataDir).canonicalFile
        val files = filesDir
        check(files.canonicalFile == data.resolve("files"))
        check(Files.isDirectory(files.toPath(), NOFOLLOW_LINKS))
        val parent = files.canonicalFile.resolve("safe-test-runs")
        if (!Files.exists(parent.toPath(), NOFOLLOW_LINKS)) {
            try {
                Files.createDirectory(parent.toPath())
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // Validate the winner below if another isolated process created it.
            }
        }
        check(Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS))
        check(parent.canonicalFile == files.canonicalFile.resolve("safe-test-runs"))
        val root = parent.resolve("SAFE-TEST-${System.currentTimeMillis()}-${UUID.randomUUID()}")
        Files.createDirectory(root.toPath())
        runDirectory = root
    }

    override fun createLayouts() = LayoutRepository(PreferenceDataStoreFactory.create {
        runDirectory.resolve("instrument_layout.preferences_pb")
    })

    override fun createSongs() = SongLibraryRepository(newTestDirectory("song_library"))

    override fun createRootRunner() = RootCommandRunner { _, _ ->
        RootCommandResult.Failure("Root is disabled in the isolated test host")
    }

    fun newTestDirectory(name: String): File {
        require(name.matches(Regex("[a-zA-Z0-9_-]+")))
        check(Files.isDirectory(runDirectory.toPath(), NOFOLLOW_LINKS))
        check(runDirectory.canonicalFile == runDirectory.absoluteFile)
        return Files.createDirectory(runDirectory.resolve(name).toPath()).toFile()
    }
}
