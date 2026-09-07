package com.lin0721.linmusic.core.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

internal fun contrastRatio(foreground: Color, background: Color): Double {
    val fg = foreground.compositeOver(background).luminance().toDouble()
    val bg = background.luminance().toDouble()
    return (maxOf(fg, bg) + 0.05) / (minOf(fg, bg) + 0.05)
}

fun readableContentColor(background: Color): Color =
    if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) Color.White else Color.Black

/** Cover colors remain recognisable while giving white overlay text a predictable contrast. */
internal fun readableBackdrop(color: Color): Color {
    if (contrastRatio(Color.White, color) >= 7.0) return color
    var low = 0f
    var high = 1f
    repeat(16) {
        val fraction = (low + high) / 2f
        if (contrastRatio(Color.White, lerp(color, Color.Black, fraction)) >= 7.0) high = fraction else low = fraction
    }
    return lerp(color, Color.Black, high)
}
