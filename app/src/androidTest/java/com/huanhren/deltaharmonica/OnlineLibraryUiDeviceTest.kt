package com.huanhren.deltaharmonica

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.huanhren.deltaharmonica.online.OnlineCatalog
import com.huanhren.deltaharmonica.online.OnlineEntry
import com.huanhren.deltaharmonica.online.OnlineIndex
import com.huanhren.deltaharmonica.online.OnlineScore
import com.huanhren.deltaharmonica.midi.MonophonyPolicy
import com.huanhren.deltaharmonica.ui.OnlineConversionOptions
import com.huanhren.deltaharmonica.ui.OnlineDownloadStatus
import com.huanhren.deltaharmonica.ui.OnlineLibraryContent
import com.huanhren.deltaharmonica.ui.OnlineSongStage
import com.huanhren.deltaharmonica.ui.theme.DeltaTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.basic.Scaffold

@RunWith(AndroidJUnit4::class)
class OnlineLibraryUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val score = OnlineEntry(
        id = "score-test", title = "竹林 Melody", composer = "Test Composer", uploader = "Test Uploader",
        tags = listOf("轻音乐"), difficulty = 2, bpm = 120.0, durationMs = 2800, noteCount = 8, updatedAt = 10,
    )
    private val alternate = score.copy(id = "score-alt", title = "Rainfall")
    private val catalog = OnlineCatalog(
        entries = listOf(score, alternate), stale = false,
        index = OnlineIndex(1, 2, 10, mapOf("0" to 2)),
    )

    @Before fun startHost() {
        requireIsolatedTestHost()
        startIsolatedMainActivity()
    }

    @After fun closeHost() = closeIsolatedActivities()

    @Test fun opensOnlineLibraryFromLocalLibraryAndReturns() {
        awaitTag("library/online")
        compose.onNodeWithTag("library/online").performClick()
        awaitTag("online/library")
        compose.onNodeWithTag("online/back").performClick()
        awaitTag("library/list")
    }

    @Test fun searchSelectDownloadRetryAndPlay() {
        val downloads = mutableStateOf(emptyMap<String, OnlineDownloadStatus>())
        val downloadRelease = CompletableDeferred<Unit>()
        var attempts = 0
        var starts = 0
        var loadCalls = 0
        var downloadOptions: OnlineConversionOptions? = null
        setContent {
            OnlineLibraryContent(
                downloaded = downloads.value,
                canPrepare = true,
                loadCatalog = { catalog },
                loadDetail = { _, _, _ ->
                    loadCalls++
                    OnlineScore(score, "fixture", false)
                },
                clearCache = {},
                prepare = { entry, _, play, _, stage, progress, options ->
                    if (!play) {
                        attempts++
                        downloadOptions = options
                        stage(OnlineSongStage.DOWNLOADING)
                        progress(64, 128)
                        if (attempts == 1) {
                            downloadRelease.await()
                            error("Test download failed")
                        }
                        stage(OnlineSongStage.SAVING)
                        downloads.value = mapOf(entry.id to OnlineDownloadStatus(entry.updatedAt, options ?: defaultOptions))
                    }
                },
                onBack = {},
                onPlay = { starts++ },
            )
        }

        awaitTag("online/song/score-test")
        compose.onNodeWithTag("online/search").performTextInput("  竹林 MELODY  ")
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTestTag("online/song/score-alt")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("online/song/score-alt").assertDoesNotExist()
        compose.onNodeWithTag("online/song/score-test").performClick()
        detailAction("online/conversion-options").performClick()
        awaitTag("online/apply-conversion")
        compose.onNodeWithText("+").performClick()
        compose.onNodeWithTag("online/apply-conversion").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTestTag("online/apply-conversion")).fetchSemanticsNodes().isEmpty()
        }
        detailAction("online/download").performClick()
        awaitTag("online/transfer")
        detailAction("online/transfer").assertIsDisplayed()
        downloadRelease.complete(Unit)
        detailAction("online/retry-transfer").performClick()
        awaitTag("online/downloaded")
        detailAction("online/play").performClick()
        compose.runOnIdle {
            assertEquals(2, attempts)
            assertEquals(1, starts)
            assertEquals(1, loadCalls)
            assertEquals(defaultOptions.copy(transpose = 1), downloadOptions)
        }
    }

    @Test fun cachedCatalogAndDownloadedDetailsPlayWithoutDetailRequest() {
        var detailRequests = 0
        var starts = 0
        setContent {
            OnlineLibraryContent(
                downloaded = mapOf(score.id to OnlineDownloadStatus(score.updatedAt, defaultOptions)),
                canPrepare = true,
                loadCatalog = { catalog.copy(stale = true) },
                loadDetail = { _, _, _ -> detailRequests++; error("Device is offline") },
                clearCache = {},
                prepare = { _, _, _, _, _, _, _ -> },
                onBack = {},
                onPlay = { starts++ },
            )
        }

        awaitTag("online/cached")
        compose.onNodeWithTag("online/catalog-list").performScrollToNode(hasTestTag("online/song/score-test"))
        compose.onNodeWithTag("online/song/score-test").performClick()
        detailAction("online/play").performClick()
        compose.runOnIdle {
            assertEquals(0, detailRequests)
            assertEquals(1, starts)
        }
        compose.onNodeWithTag("online/back").performClick()
        awaitTag("online/catalog-list")
    }

    @Test fun returningToCatalogCancelsInFlightDetail() {
        val requestCancelled = CompletableDeferred<Unit>()
        setContent {
            OnlineLibraryContent(
                downloaded = emptyMap(),
                canPrepare = true,
                loadCatalog = { catalog },
                loadDetail = { _, _, _ ->
                    try {
                        awaitCancellation()
                    } finally {
                        requestCancelled.complete(Unit)
                    }
                },
                clearCache = {},
                prepare = { _, _, _, _, _, _, _ -> },
                onBack = {},
                onPlay = {},
            )
        }

        awaitTag("online/song/score-test")
        compose.onNodeWithTag("online/song/score-test").performClick()
        awaitTag("online/transfer")
        compose.onNodeWithTag("online/back").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { requestCancelled.isCompleted }
        awaitTag("online/catalog-list")
    }

    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            resumedIsolatedActivity().setContent {
                DeltaTheme {
                    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { content() }
                }
            }
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun detailAction(tag: String) = compose.onNodeWithTag(tag).also {
        awaitTag("online/details")
        compose.onNodeWithTag("online/details").performScrollToNode(hasTestTag(tag))
    }

    private companion object {
        val defaultOptions = OnlineConversionOptions(MonophonyPolicy.HIGHEST_NOTE, 0)
    }
}
