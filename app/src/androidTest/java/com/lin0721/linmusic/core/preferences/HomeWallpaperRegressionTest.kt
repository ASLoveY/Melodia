package com.lin0721.linmusic.core.preferences

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lin0721.linmusic.core.auth.UserProfile
import com.lin0721.linmusic.core.ui.theme.MelodiaTheme
import com.lin0721.linmusic.core.ui.theme.WallpaperContent
import com.lin0721.linmusic.feature.home.ui.HomeSharedHeader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeWallpaperRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun headerAndContentShareWallpaperForSignedInAndGuestAcrossTabsAndThemes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "header-wallpaper-regression.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        var dark by mutableStateOf(false)
        var tab by mutableIntStateOf(0)
        var profile by mutableStateOf<UserProfile?>(null)
        try {
            compose.setContent {
                MelodiaTheme(darkTheme = dark) {
                    WallpaperContent(BackgroundSettings(file.absolutePath, 0), true) {
                        Column(Modifier.fillMaxSize()) {
                            HomeSharedHeader(profile, tab, { tab = it }, false, {}, {}, {})
                            Box(Modifier.fillMaxSize())
                        }
                    }
                }
            }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("app_wallpaper").fetchSemanticsNodes().isNotEmpty() }
            for (night in listOf(false, true)) for (signedIn in listOf(false, true)) for (selectedTab in 0..2) {
                compose.runOnIdle {
                    dark = night; tab = selectedTab
                    profile = if (signedIn) UserProfile(1, "测试用户", "") else null
                }
                compose.waitForIdle()
                val header = compose.onNodeWithTag("home_shared_header").fetchSemanticsNode().boundsInRoot
                val pixels = compose.onRoot().captureToImage().toPixelMap()
                val above = pixels[2, (header.bottom - 5).toInt()]
                val below = pixels[2, (header.bottom + 5).toInt()]
                val top = pixels[2, (header.top + 10).toInt()]
                for (color in listOf(above, top)) {
                    assertEquals("Header masks wallpaper: dark=$night tab=$tab", below.red, color.red, .015f)
                    assertEquals(below.green, color.green, .015f)
                    assertEquals(below.blue, color.blue, .015f)
                }
            }
        } finally { file.delete() }
    }
}
