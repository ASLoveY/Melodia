package com.lin0721.linmusic.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test fun wallpaperTextRemainsReadableOnControlSurfaces() {
        for (base in listOf(MelodiaLightColors, MelodiaDarkColors)) {
            val colors = wallpaperColors(base)
            for (background in listOf(base.surface, base.surfaceVariant, base.surfaceContainer)) {
                for (text in listOf(colors.onBackground, colors.onSurface, colors.onSurfaceVariant, colors.primary)) {
                    assertTrue("$text on $background", contrastRatio(text, background) >= 4.5)
                }
            }
        }
    }
    @Test fun bodySecondaryAndAccentTextHaveReadableContrastInBothThemes() {
        for (scheme in listOf(MelodiaLightColors, MelodiaDarkColors)) {
            val surfaces = listOf(scheme.background, scheme.surface, scheme.surfaceVariant,
                scheme.surfaceContainer, scheme.surfaceContainerHighest)
            for (surface in surfaces) {
                for (text in listOf(scheme.onSurface, scheme.onSurfaceVariant, scheme.primary)) {
                    assertTrue("Insufficient contrast: $text on $surface", contrastRatio(text, surface) >= 4.5)
                }
            }
            for ((text, background) in listOf(
                scheme.onPrimary to scheme.primary,
                scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onSecondary to scheme.secondary,
                scheme.onSecondaryContainer to scheme.secondaryContainer,
                scheme.onError to scheme.error
            )) assertTrue(contrastRatio(text, background) >= 4.5)
        }
    }

    @Test fun brightArtworkIsDarkenedEnoughForWhiteLabels() {
        for (color in listOf(Color.White, Color.Yellow, Color.Cyan, Color.Green, Color.Magenta, Color.Red, Color.Blue)) {
            assertTrue(contrastRatio(Color.White, readableBackdrop(color)) >= 7.0)
        }
    }

    @Test fun adaptiveChipTextIsReadableOnLightAndDarkFills() {
        for (fill in listOf(Color.White, Color.Black, Color.Yellow, Color.Red, Color(0xFF888888))) {
            assertTrue(contrastRatio(readableContentColor(fill), fill) >= 4.5)
        }
    }
}
