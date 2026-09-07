package com.lin0721.linmusic.feature.local.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.test.performTouchInput
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

    @Test
    fun selectionToolbarSelectAllAndBatchRemove() {
        val tracks = listOf(
            track("one", "第一首", "甲歌手", "甲专辑"),
            track("two", "第二首", "乙歌手", "乙专辑")
        )
        var selectAll = false
        var removed: Set<String>? = null
        composeRule.setContent {
            MaterialTheme {
                LocalMusicList(
                    tracks = tracks,
                    totalCount = tracks.size,
                    query = "",
                    sort = LocalMusicSort.RECENT,
                    importing = false,
                    error = null,
                    playingId = null,
                    isPlaying = false,
                    onQuery = {},
                    onSort = {},
                    onImport = {},
                    onPlay = {},
                    onRemove = {},
                    selectionActive = true,
                    selectedIds = setOf("one"),
                    onToggleSelectAll = { selectAll = true },
                    onRemoveSelected = { removed = it }
                )
            }
        }

        composeRule.onNodeWithTag("local_select_all").performClick()
        composeRule.onNodeWithTag("local_remove_selected").performClick()
        composeRule.runOnIdle {
            assertTrue(selectAll)
            assertEquals(setOf("one"), removed)
        }
    }

    @Test
    fun selectionActionsDisabledWhileBusy() {
        val tracks = listOf(track("one", "第一首", "甲歌手", "甲专辑"))
        composeRule.setContent {
            MaterialTheme {
                LocalMusicList(
                    tracks = tracks,
                    totalCount = tracks.size,
                    query = "",
                    sort = LocalMusicSort.RECENT,
                    importing = false,
                    error = null,
                    playingId = null,
                    isPlaying = false,
                    onQuery = {},
                    onSort = {},
                    onImport = {},
                    onPlay = {},
                    onRemove = {},
                    selectionActive = true,
                    selectedIds = setOf("one"),
                    busy = true
                )
            }
        }

        composeRule.onNodeWithTag("local_select_all").assertIsNotEnabled()
        composeRule.onNodeWithTag("local_remove_selected").assertIsNotEnabled()
        composeRule.onNodeWithTag("local_exit_selection").assertIsNotEnabled()
    }

    @Test
    fun directoryEntryCallbackIsExposed() {
        var opened = false
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
                    onPlay = {},
                    onRemove = {},
                    directoryCount = 2,
                    onOpenDirectories = { opened = true }
                )
            }
        }

        composeRule.onNodeWithTag("local_directories").performClick()
        composeRule.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun longPressDragSelectionSurvivesHeaderRecompositionAndDoesNotPlay() {
        val tracks = listOf(
            track("one", "第一首", "甲歌手", "甲专辑"),
            track("two", "第二首", "乙歌手", "乙专辑"),
            track("three", "第三首", "丙歌手", "丙专辑")
        )
        val controller = LocalSelectionController()
        var played = 0

        composeRule.setContent {
            val selection by controller.state.collectAsState()
            LaunchedEffect(Unit) { controller.setItems(tracks.map { it.id }) }
            MaterialTheme {
                LocalMusicList(
                    tracks = tracks,
                    totalCount = tracks.size,
                    query = "",
                    sort = LocalMusicSort.RECENT,
                    importing = false,
                    error = null,
                    playingId = null,
                    isPlaying = false,
                    onQuery = {},
                    onSort = {},
                    onImport = {},
                    onPlay = { played++ },
                    onRemove = {},
                    selectionActive = selection.active,
                    selectedIds = selection.selectedIds,
                    onToggleSelection = controller::toggle,
                    onStartDragSelection = controller::beginDrag,
                    onDragOverSelection = controller::dragTo,
                    onEndDragSelection = controller::endDrag
                )
            }
        }

        val first = composeRule.onNodeWithTag("local_row_one").fetchSemanticsNode().boundsInRoot
        val third = composeRule.onNodeWithTag("local_row_three").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("local_row_one").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveTo(center.copy(y = center.y + third.center.y - first.center.y))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(setOf("one", "two", "three"), controller.state.value.selectedIds)
            assertEquals(0, played)
        }
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
