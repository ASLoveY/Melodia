package com.lin0721.linmusic.feature.player.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.lin0721.linmusic.core.player.PlayMode
import com.lin0721.linmusic.core.player.QueueItem
import com.lin0721.linmusic.core.ui.theme.MelodiaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RoamingQueueSheetTest {
    @get:Rule val compose = createComposeRule()
    @Test fun roamingShowsFollowingSongsAndSelectsTheirActualQueueIndex() {
        var selected = -1
        compose.setContent {
            MelodiaTheme {
                PlayQueueSheet(
                    queue = listOf(QueueItem(1, "current", "artist", ""), QueueItem(2, "next a", "artist", ""), QueueItem(3, "next b", "artist", "")),
                    currentIndex = 0, playMode = PlayMode.LIST_LOOP, playContext = "similar_roaming", isPlaying = false,
                    onPlayAtIndex = { selected = it }, onRemoveAtIndex = {}, onMoveItem = { _, _ -> },
                    onToggleShuffle = {}, onClearQueue = {}, onDisableRoaming = {}, onDismiss = {}
                )
            }
        }
        compose.onNodeWithText("接下来播放").assertIsDisplayed()
        compose.onNodeWithText("next a").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, selected) }
        compose.onNodeWithText("next b").assertExists()
    }
}
