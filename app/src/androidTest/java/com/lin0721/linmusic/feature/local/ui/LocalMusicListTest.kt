package com.lin0721.linmusic.feature.local.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import com.lin0721.linmusic.feature.local.domain.LocalTrack
import com.lin0721.linmusic.feature.local.domain.LocalImportProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class LocalMusicListTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun localMusicListSearchPlayAndRemoveCallbacks() {
        val tracks = listOf(
            track("one", "第一首", "甲歌手", "甲专辑"),
            track("two", "第二首", "乙歌手", "乙专辑")
        )
        var query by mutableStateOf("")
        var played: LocalTrack? = null
        var removed: LocalTrack? = null

        composeRule.setContent {
            MaterialTheme {
                LocalMusicList(
                    tracks = tracks,
                    totalCount = tracks.size,
                    query = query,
                    sort = LocalMusicSort.RECENT,
                    importing = false,
                    error = null,
                    playingId = null,
                    isPlaying = false,
                    onQuery = { query = it },
                    onSort = {},
                    onImport = {},
                    onPlay = { played = it },
                    onRemove = { removed = it }
                )
            }
        }

        composeRule.onNodeWithTag("local_search").performTextInput("甲歌手")
        composeRule.onNode(hasClickAction().and(hasText("第一首"))).performClick()
        composeRule.onNodeWithContentDescription("更多操作：第一首").performClick()

        composeRule.runOnIdle {
            assertEquals("甲歌手", query)
            assertSame(tracks[0], played)
            assertSame(tracks[0], removed)
        }
    }

    @Test
    fun localMusicImportButtonDisabledWhileImporting() {
        var cancelRequested = false
        composeRule.setContent {
            MaterialTheme {
                LocalMusicList(
                    tracks = emptyList(),
                    totalCount = 0,
                    query = "",
                    sort = LocalMusicSort.RECENT,
                    importing = true,
                    importProgress = LocalImportProgress(scanned = 3, imported = 1, skippedShort = 1),
                    error = null,
                    playingId = null,
                    isPlaying = false,
                    onQuery = {},
                    onSort = {},
                    onImport = {},
                    onImportDirectory = {},
                    onCancelImport = { cancelRequested = true },
                    onPlay = {},
                    onRemove = {}
                )
            }
        }

        composeRule.onNodeWithTag("local_import").assertIsNotEnabled()
        composeRule.onNodeWithTag("local_import_directory").assertIsNotEnabled()
        composeRule.onNodeWithTag("local_import_cancel").performClick()
        composeRule.runOnIdle { assertTrue(cancelRequested) }
    }

    @Test
    fun localMusicSavingStageDisablesCancellation() {
        var cancelRequested = false
        composeRule.setContent {
            MaterialTheme {
                LocalMusicList(
                    tracks = emptyList(),
                    totalCount = 0,
                    query = "",
                    sort = LocalMusicSort.RECENT,
                    importing = true,
                    importProgress = LocalImportProgress(
                        scanned = 3,
                        imported = 2,
                        skippedShort = 1,
                        isSaving = true
                    ),
                    error = null,
                    playingId = null,
                    isPlaying = false,
                    onQuery = {},
                    onSort = {},
                    onImport = {},
                    onImportDirectory = {},
                    onCancelImport = { cancelRequested = true },
                    onPlay = {},
                    onRemove = {}
                )
            }
        }

        composeRule.onNodeWithTag("local_import_cancel").assertIsNotEnabled()
        composeRule.runOnIdle { assertFalse(cancelRequested) }
    }

    @Test
    fun localMusicDirectoryImportCallback() {
        var requested = false
        composeRule.setContent {
            MaterialTheme {
                LocalMusicList(
                    tracks = emptyList(),
                    totalCount = 0,
                    query = "",
                    sort = LocalMusicSort.RECENT,
                    importing = false,
                    error = null,
                    playingId = null,
                    isPlaying = false,
                    onQuery = {},
                    onSort = {},
                    onImport = {},
                    onImportDirectory = { requested = true },
                    onCancelImport = {},
                    onPlay = {},
                    onRemove = {}
                )
            }
        }

        composeRule.onNodeWithTag("local_import_directory").performClick()
        composeRule.runOnIdle { assertEquals(true, requested) }
    }

    private fun track(
        id: String,
        title: String,
        artist: String,
        album: String
    ) = LocalTrack(
        id = id,
        uri = "content://local/$id",
        title = title,
        artist = artist,
        album = album,
        addedAt = 1L
    )
}
