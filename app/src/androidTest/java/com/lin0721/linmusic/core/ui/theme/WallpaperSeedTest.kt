package com.lin0721.linmusic.core.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lin0721.linmusic.core.preferences.BackgroundSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class WallpaperSeedTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun picture(name: String, color: Int): File {
        val bitmap = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return File(context.cacheDir, name).also { file ->
            try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
        }
    }
    @Test fun actualImagesProduceDifferentSeedsAndMissingImageIsSafe() = runBlocking {
        val red = picture("palette-red.png", android.graphics.Color.RED)
        val blue = picture("palette-blue.png", android.graphics.Color.BLUE)
        try {
            val r = requireNotNull(wallpaperSeed(context, red.path)); val b = requireNotNull(wallpaperSeed(context, blue.path))
            assertTrue(r.red > .8f && r.blue < .2f); assertTrue(b.blue > .8f && b.red < .2f)
            assertNull(wallpaperSeed(context, File(context.cacheDir, "missing-palette-image.png").path))
        } finally { red.delete(); blue.delete() }
    }
    @Test fun wallpaperThemeFollowsModeAndDoesNotTintExcludedPages() {
        val file = picture("palette-theme.png", android.graphics.Color.BLUE)
        val seed = runBlocking { requireNotNull(wallpaperSeed(context, file.path)) }
        var dark by mutableStateOf(false); var enabled by mutableStateOf(true)
        var primary by mutableStateOf(Color.Unspecified)
        compose.setContent {
            MelodiaTheme(darkTheme = dark) {
                WallpaperContent(BackgroundSettings(imagePath = file.path), enabled) {
                    val colors = MaterialTheme.colorScheme
                    SideEffect { primary = colors.primary }
                }
            }
        }
        try {
            val light = wallpaperScheme(wallpaperColors(MelodiaLightColors), seed).primary
            compose.waitUntil(8000) { primary == light }
            compose.runOnIdle { dark = true }
            val expectedDark = wallpaperScheme(wallpaperColors(MelodiaDarkColors), seed).primary
            compose.waitUntil(8000) { primary == expectedDark }
            compose.runOnIdle { enabled = false }
            compose.waitUntil(8000) { primary == MelodiaDarkColors.primary }
        } finally { file.delete() }
    }
}
