package com.lin0721.linmusic

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Small real-UI smoke suite. Network responses are deliberately not part of
 * these assertions; the tests only exercise app-owned navigation and state.
 */
@RunWith(AndroidJUnit4::class)
class AppSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun grantNotificationPermissionWhenAvailable() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                    InstrumentationRegistry.getInstrumentation().targetContext.packageName,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }
    }

    @Test
    fun launchShowsPrimaryNavigation() {
        waitForText("主页")

        composeRule.onNodeWithText("主页").assertIsDisplayed()
        composeRule.onNodeWithText("搜索").assertIsDisplayed()
        composeRule.onNodeWithText("音乐库").assertIsDisplayed()
        composeRule.onNodeWithText("创建").assertIsDisplayed()
    }

    @Test
    fun libraryShowsLocalMusicWithoutLogin() {
        waitForText("音乐库")
        composeRule.onNodeWithText("音乐库").performClick()
        waitForText("本地音乐")
        composeRule.onNodeWithText("本地音乐").performClick()
        waitForText("导入音乐")
        composeRule.onNodeWithText("导入音乐").assertIsDisplayed()
    }

    @Test
    fun unauthenticatedCreateOpensLoginAndBackRestoresPage() {
        waitForText("创建")
        composeRule.onNodeWithText("创建").performClick()
        waitForText("歌单")

        // The unfinished collaboration entries are intentionally absent.
        assertTrue(composeRule.onAllNodesWithText("共建歌单").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("共享合辑").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithText("歌单").performClick()
        waitForText("登录")
        composeRule.onNodeWithText("网页登录").assertIsDisplayed()

        composeRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("网页登录").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("主页").assertIsDisplayed()
        composeRule.onNodeWithText("创建").assertIsDisplayed()
    }

    @Test
    fun searchEntryAcceptsTextAndSwitchesCategory() {
        waitForText("搜索")
        composeRule.onNodeWithContentDescription("搜索").performClick()

        waitForText("搜索")
        tapSearchField()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasSetTextAction()).performTextInput("smoke")

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("单曲").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("专辑").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("专辑").performClick()
        composeRule.onNodeWithText("专辑").assertIsDisplayed()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The tag marks the product's existing search entry in both inactive and active states. */
    private fun tapSearchField() {
        composeRule.onNodeWithTag("search_input").performClick()
    }
}
