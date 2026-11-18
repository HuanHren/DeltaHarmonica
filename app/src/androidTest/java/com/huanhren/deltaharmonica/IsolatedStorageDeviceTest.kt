package com.huanhren.deltaharmonica

import android.content.ComponentName
import android.content.pm.PackageManager
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huanhren.deltaharmonica.accessibility.TouchAccessibilityService
import com.huanhren.deltaharmonica.input.BackendAvailability
import com.huanhren.deltaharmonica.overlay.CalibrationOverlayService
import com.huanhren.deltaharmonica.model.DefaultLayouts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class IsolatedStorageDeviceTest {
    private val app get() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as IsolatedTestApplication

    @Test fun testHostCannotUsePrivilegedInputOrGlobalOverlay() = runBlocking {
        requireIsolatedTestHost()
        assertEquals(BackendAvailability.NOT_ENABLED, app.rootBackend.checkAccess().availability)
        for (service in listOf(TouchAccessibilityService::class.java, CalibrationOverlayService::class.java)) {
            assertThrows(PackageManager.NameNotFoundException::class.java) {
                app.packageManager.getServiceInfo(ComponentName(app, service), 0)
            }
        }
    }

    @Test fun runDirectoryRejectsTraversalAndSymlink() {
        requireIsolatedTestHost()
        assertThrows(IllegalArgumentException::class.java) { app.newTestDirectory("../sibling") }
        val link = app.runDirectory.resolve("outside_link")
        Os.symlink(app.filesDir.absolutePath, link.absolutePath)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertThrows(java.nio.file.FileAlreadyExistsException::class.java) { app.newTestDirectory(link.name) }
        assertEquals(app.runDirectory, app.newTestDirectory("storage_fixture").canonicalFile.parentFile)
    }

    @Test fun storesUseTheRunDirectory() = runBlocking {
        requireIsolatedTestHost()
        val layout = DefaultLayouts.create(1)
        app.layouts.save(layout)
        assertEquals(layout, app.layouts.load())
        assertTrue(app.runDirectory.resolve("instrument_layout.preferences_pb").isFile)
        app.songs.refresh()
        assertEquals(app.runDirectory, app.runDirectory.resolve("song_library").canonicalFile.parentFile)
        assertTrue(app.runDirectory.resolve("song_library").isDirectory)
    }
}
