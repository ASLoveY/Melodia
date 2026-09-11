package com.lin0721.linmusic.core.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class WallpaperThemeColorsTest {
    @Test fun accentRolesAndTheirForegroundsRemainReadableAcrossSeedsAndThemes() {
        for (base in listOf(MelodiaDarkColors, MelodiaLightColors)) {
            for (seed in listOf(Color.Red, Color.Blue, Color.Green, Color.Yellow, Color.Black, Color.White, Color.Gray)) {
                val colors = wallpaperScheme(base, seed)
                val pairs = listOf(colors.primary to colors.onPrimary, colors.primaryContainer to colors.onPrimaryContainer,
                    colors.secondaryContainer to colors.onSecondaryContainer, colors.tertiaryContainer to colors.onTertiaryContainer,
                    colors.surfaceVariant to colors.onSurfaceVariant)
                for ((background, text) in pairs) { assertEquals(1f, background.alpha); assertTrue(contrastRatio(text, background) >= 4.5) }
                for (accent in listOf(colors.primary, colors.secondary, colors.tertiary)) {
                    assertTrue(contrastRatio(accent, base.surface) >= 4.5)
                    assertTrue(contrastRatio(accent, base.background) >= 4.5)
                }
            }
        }
    }
    @Test fun seedAndThemeBothInfluenceColorsWithoutChangingOpaqueSurfaces() {
        val red = wallpaperScheme(MelodiaLightColors, Color.Red)
        val blue = wallpaperScheme(MelodiaLightColors, Color.Blue)
        val dark = wallpaperScheme(MelodiaDarkColors, Color.Red)
        assertNotEquals(red.primary, blue.primary); assertNotEquals(red.primary, dark.primary)
        assertEquals(MelodiaLightColors.surface, red.surface)
        assertEquals(MelodiaLightColors.background, red.background)
    }
    @Test fun monochromeImagesDoNotInventSaturatedAccents() {
        val colors = wallpaperScheme(MelodiaDarkColors, Color.Gray)
        assertEquals(colors.primary.red, colors.primary.green, .001f)
        assertEquals(colors.primary.green, colors.primary.blue, .001f)
    }
}
