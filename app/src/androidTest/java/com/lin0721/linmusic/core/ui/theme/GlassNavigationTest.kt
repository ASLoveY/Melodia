package com.lin0721.linmusic.core.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.lin0721.linmusic.Screen
import com.lin0721.linmusic.core.preferences.BackgroundSettings
import com.lin0721.linmusic.core.ui.components.MelodiaNavigationBar
import dev.chrisbanes.haze.HazeState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GlassNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun glassTabsRemainClickableAcrossThemeChangesAndCreateVisibility() {
        var screen by mutableStateOf(Screen.Home)
        var dark by mutableStateOf(false)
        var create by mutableStateOf(false)
        var showCreate by mutableStateOf(true)
        val haze = HazeState()
        compose.setContent {
            MelodiaTheme(darkTheme = dark) {
                WallpaperContent(BackgroundSettings(), true, haze, overlay = {
                    MelodiaNavigationBar(screen, { screen = it }, { create = !create }, create,
                        showCreateEntry = showCreate, hazeState = haze)
                }) { Box(Modifier.fillMaxSize()) }
            }
        }
        for (night in listOf(false, true)) {
            compose.runOnIdle { dark = night }
            compose.onNodeWithText("搜索").performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(Screen.Search, screen); assertEquals(1, haze.areas.size) }
            compose.onNodeWithText("音乐库").performClick().assertIsSelected()
            compose.onNodeWithText("创建").performClick().assertIsSelected()
            compose.onNodeWithText("创建").performClick().assertIsNotSelected()
        }
        compose.runOnIdle { showCreate = false }
        compose.onNodeWithText("创建").assertDoesNotExist()
        compose.onNodeWithText("主页").performClick().assertIsSelected()
    }
}
