package com.lin0721.linmusic.feature.library.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LibraryPlaylistActionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun playlistActionsRespectOwnershipAndProtectedEntries() {
        val displayedItem = mutableStateOf(item(id = "123", ownerId = 42L, isOwnedByMe = true))
        composeRule.setContent {
            MaterialTheme {
                LibraryItemActionsSheet(
                    item = displayedItem.value,
                    onDismiss = {},
                    onTogglePin = {},
                    onRemovePlaylist = {}
                )
            }
        }
        composeRule.onNodeWithText("删除歌单").assertIsDisplayed()

        composeRule.runOnIdle { displayedItem.value = item(id = "123", ownerId = 99L, isOwnedByMe = false) }
        composeRule.onNodeWithText("取消收藏").assertIsDisplayed()
        composeRule.onNodeWithText("删除歌单").assertDoesNotExist()

        composeRule.runOnIdle { displayedItem.value = item(id = "456", ownerId = 42L, isLikedSongs = true) }
        composeRule.onNodeWithText("删除歌单").assertDoesNotExist()

        composeRule.runOnIdle { displayedItem.value = item(id = "-2", ownerId = 42L) }
        composeRule.onNodeWithText("删除歌单").assertDoesNotExist()
    }

    @Test
    fun cancellingDoesNotConfirmRemoval() {
        var confirmed = false
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                LibraryPlaylistRemovalDialog(
                    state = confirmState(),
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true }
                )
            }
        }

        composeRule.onNode(hasClickAction().and(hasText("取消"))).performClick()

        assertFalse(confirmed)
        assertTrue(dismissed)
    }

    @Test
    fun submittingDisablesConfirmationAndCancellation() {
        var confirmed = false
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                LibraryPlaylistRemovalDialog(
                    state = confirmState(isSubmitting = true),
                    onConfirm = { confirmed = true },
                    onDismiss = { dismissed = true }
                )
            }
        }

        composeRule
            .onNode(hasClickAction().and(hasText("删除歌单")))
            .assertIsNotEnabled()
        composeRule
            .onNode(hasClickAction().and(hasText("取消")))
            .assertIsNotEnabled()

        assertFalse(confirmed)
        assertFalse(dismissed)
    }

    private fun item(
        id: String,
        ownerId: Long,
        isOwnedByMe: Boolean = false,
        isLikedSongs: Boolean = false
    ) = LibraryItem(
        id = id,
        title = "测试歌单",
        subtitle = "测试",
        coverUrl = "",
        type = LibraryItemType.PLAYLIST,
        isOwnedByMe = isOwnedByMe,
        isLikedSongs = isLikedSongs,
        ownerId = ownerId
    )

    private fun confirmState(isSubmitting: Boolean = false) = PlaylistRemovalState.Confirm(
        target = PlaylistRemovalTarget(
            id = 123L,
            title = "测试歌单",
            ownerId = 42L
        ),
        kind = PlaylistRemovalKind.DELETE,
        isSubmitting = isSubmitting
    )
}
