package com.huanhren.deltaharmonica

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule


import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huanhren.deltaharmonica.data.DocumentTransfer
import com.huanhren.deltaharmonica.midi.*
import com.huanhren.deltaharmonica.model.Region
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

private val providerUri: Uri get() = Uri.parse("content://${InstrumentationRegistry.getInstrumentation().targetContext.packageName}.importprovider")

@RunWith(AndroidJUnit4::class)
class ImportLifecycleDeviceTest {
    @Before fun checkIsolatedHost() = requireIsolatedTestHost()
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val resolver get() = instrumentation.targetContext.contentResolver
    private val name = "test-import-${UUID.randomUUID()}.json"
    private val script = PerformanceScript(1,"profile","fingerprint",name,0,MonophonyPolicy.HIGHEST_NOTE,false,
        listOf(ScriptEvent(0,120,60,"NOTE_1",Region.NATURAL,false)))

    private fun waitForRecreatedActivity(oldActivity: MainActivity) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        do {
            var recreated = false
            instrumentation.runOnMainSync {
                recreated = runCatching { resumedIsolatedActivity() !== oldActivity }.getOrDefault(false)
            }
            if (recreated) return
            android.os.SystemClock.sleep(100)
        } while (android.os.SystemClock.elapsedRealtime() < deadline)
        error("The isolated activity did not recreate")
    }

    @After fun releaseProviderAndPreserveTestEvidence() {
        requireIsolatedTestHost()
        resolver.call(providerUri,"release",null,null)
    }

    @Test(timeout = 45_000) fun navigationAndRecreationKeepTheSameImportWithoutDuplicate() {
        startIsolatedMainActivity()
        resolver.call(providerUri,"configure",null,Bundle().apply { putByteArray("bytes",ScriptCodec.encode(script).toByteArray()); putString("name",name) })
        val uri = providerUri.buildUpon().appendPath("normal").build()
        try {
            compose.onNodeWithTag("home/import").performClick()
            instrumentation.runOnMainSync {
                val app = resumedIsolatedActivity().application as DeltaHarmonicaApp
                assertTrue(app.model.importDocument(uri))
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("正在读取文件…").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("选择 MIDI / JSON 文件").assertIsNotEnabled()
            compose.onNodeWithText("返回").performClick()
            compose.onNodeWithTag("nav/settings").performClick()
            compose.onNodeWithTag("nav/home").performClick()
            compose.onNodeWithTag("home/import").performClick()
            compose.onNodeWithText("正在读取文件…").assertExists()
            var beforeRotation: MainActivity? = null
            instrumentation.runOnMainSync {
                val activity = resumedIsolatedActivity()
                if (activity.resources.configuration.orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    beforeRotation = activity
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
            }
            beforeRotation?.let(::waitForRecreatedActivity)
            compose.onNodeWithText("正在读取文件…").assertExists()
            lateinit var oldActivity: MainActivity
            instrumentation.runOnMainSync {
                oldActivity = resumedIsolatedActivity()
                oldActivity.recreate()
            }
            waitForRecreatedActivity(oldActivity)
            compose.onNodeWithText("正在读取文件…").assertExists()
            resolver.call(providerUri,"release",null,null)
            compose.waitUntil(20_000) { compose.onAllNodesWithText("已保存到曲库，请检查风险并开始预检").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("查看曲库").performScrollTo().performClick()
            compose.onNodeWithText(name).assertExists()
            val songs = (instrumentation.targetContext.applicationContext as DeltaHarmonicaApp).songs
            assertEquals(1,songs.items.value.count { it.originalFileName == name })
        } finally {
            resolver.call(providerUri,"release",null,null)
            closeIsolatedActivities()
        }
    }
}

@RunWith(AndroidJUnit4::class)
class DocumentTransferDeviceTest {
    @Before fun checkIsolatedHost() = requireIsolatedTestHost()
    private val resolver get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
    @Test fun realResolverReadsProviderAndReportsPermissionAndMissingFile() = runBlocking {
        val expected = "content".toByteArray()
        resolver.call(providerUri,"configure",null,Bundle().apply { putByteArray("bytes",expected); putString("name","fixture.json") })
        resolver.call(providerUri,"release",null,null)
        val loaded = DocumentTransfer.read(resolver,providerUri.buildUpon().appendPath("normal").build())
        assertEquals("fixture.json",loaded.first); assertArrayEquals(expected,loaded.second)
        for (path in listOf("denied","missing","io")) {
            val failure = runCatching { DocumentTransfer.read(resolver,providerUri.buildUpon().appendPath(path).build()) }.exceptionOrNull()
            assertNotNull("$path must fail",failure)
            assertTrue("$path: $failure", if(path == "denied") failure is SecurityException else failure is java.io.FileNotFoundException)
        }
    }
}
