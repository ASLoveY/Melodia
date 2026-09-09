package com.lin0721.linmusic.core.preferences

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lin0721.linmusic.core.ui.theme.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WallpaperAppearanceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun opacityEndpointsAndExcludedPagesKeepThemeAndForegroundReadable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "wallpaper-color-test.png")
        val bitmap = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        var settings by mutableStateOf(BackgroundSettings(file.absolutePath, 0))
        var enabled by mutableStateOf(true)
        var dark by mutableStateOf(false)
        var pageColor = Color.Unspecified
        try {
            compose.setContent { MelodiaTheme(darkTheme = dark) { WallpaperContent(settings, enabled) { pageColor = AppPageBackground } } }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("app_wallpaper").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { assertEquals(Color.Transparent, pageColor) }
            fun centerColor(): Color {
                val pixels = compose.onRoot().captureToImage().toPixelMap()
                return pixels[pixels.width / 2, pixels.height / 2]
            }
            val tinted = centerColor()
            compose.runOnIdle { settings = settings.copy(transparency = 100) }
            val hidden = centerColor()
            assertNotEquals(tinted.toArgb(), hidden.toArgb())
            compose.runOnIdle { enabled = false }
            compose.onNodeWithTag("app_wallpaper").assertDoesNotExist()
            compose.runOnIdle { assertNotEquals(Color.Transparent, pageColor); dark = true; enabled = true; settings = settings.copy(transparency = 0) }
            compose.onNodeWithTag("app_wallpaper").assertExists()
            val expected = MelodiaDarkColors.background.copy(alpha = .35f).compositeOver(Color.Red)
            assertEquals(expected.red, centerColor().red, .02f)
        } finally { file.delete() }
    }
}
