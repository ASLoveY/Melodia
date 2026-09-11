package com.lin0721.linmusic.core.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Sample the app-owned image, not the translucency preview or another app's wallpaper. */
internal suspend fun wallpaperSeed(context: Context, path: String): Color? = withContext(Dispatchers.IO) {
    val drawable = context.imageLoader.execute(ImageRequest.Builder(context).data(File(path)).size(128).allowHardware(false).build()).drawable
        ?: return@withContext null
    val bitmap = drawable.toBitmap(128, 128).copy(Bitmap.Config.ARGB_8888, true) ?: return@withContext null
    try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        // Hidden RGB values in transparent PNG pixels must not determine the palette.
        for (i in pixels.indices) if ((pixels[i] ushr 24) < 128) pixels[i] = 0xff808080.toInt()
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val palette = Palette.from(bitmap).maximumColorCount(12).generate()
        val selected = palette.swatches.maxByOrNull { swatch -> swatch.population * (0.5f + swatch.hsl[1] * 0.5f) }
        if (selected != null) Color(selected.rgb) else {
            val r = pixels.sumOf { ((it ushr 16) and 255).toLong() } / pixels.size
            val g = pixels.sumOf { ((it ushr 8) and 255).toLong() } / pixels.size
            val b = pixels.sumOf { (it and 255).toLong() } / pixels.size
            Color(r.toInt(), g.toInt(), b.toInt())
        }
    } finally { bitmap.recycle() }
}

/** Theme roles stay opaque; each tinted control has its own readable foreground. */
internal fun wallpaperScheme(base: ColorScheme, seed: Color): ColorScheme {
    val high = maxOf(seed.red, seed.green, seed.blue)
    val low = minOf(seed.red, seed.green, seed.blue)
    val delta = high - low
    val hue = if (delta < 0.0001f) 0f else ((when (high) {
        seed.red -> (seed.green - seed.blue) / delta
        seed.green -> (seed.blue - seed.red) / delta + 2f
        else -> (seed.red - seed.green) / delta + 4f
    }) * 60f + 360f) % 360f
    val saturation = if (high < 0.0001f) 0f else delta / high
    val s = if (saturation < 0.08f) 0f else saturation.coerceIn(0.35f, 0.75f)
    val dark = base.background.luminance() < 0.5f
    val neutral = if (dark) Color.Black else Color.White
    fun accent(raw: Color): Color {
        fun readable(color: Color) = minOf(contrastRatio(color, base.surface), contrastRatio(color, base.background)) >= 4.5
        if (readable(raw)) return raw
        val foreground = if (dark) Color.White else Color.Black
        var lowBlend = 0f; var highBlend = 1f
        repeat(16) {
            val middle = (lowBlend + highBlend) / 2
            if (readable(lerp(raw, foreground, middle))) highBlend = middle else lowBlend = middle
        }
        return lerp(raw, foreground, highBlend)
    }
    val primary = accent(Color.hsv(hue, s, if (dark) 0.92f else 0.58f))
    val secondary = accent(Color.hsv((hue + 24f) % 360f, s * 0.65f, if (dark) 0.85f else 0.5f))
    val tertiary = accent(Color.hsv((hue + 48f) % 360f, s * 0.75f, if (dark) 0.85f else 0.5f))
    val primaryContainer = lerp(primary, neutral, if (dark) 0.65f else 0.82f)
    val secondaryContainer = lerp(secondary, neutral, if (dark) 0.68f else 0.87f)
    val tertiaryContainer = lerp(tertiary, neutral, if (dark) 0.68f else 0.87f)
    val surfaceVariant = Color.hsv(hue, s * 0.15f, if (dark) 0.23f else 0.93f)
    return base.copy(
        primary = primary, onPrimary = readableContentColor(primary),
        primaryContainer = primaryContainer, onPrimaryContainer = readableContentColor(primaryContainer),
        secondary = secondary, onSecondary = readableContentColor(secondary),
        secondaryContainer = secondaryContainer, onSecondaryContainer = readableContentColor(secondaryContainer),
        tertiary = tertiary, onTertiary = readableContentColor(tertiary),
        tertiaryContainer = tertiaryContainer, onTertiaryContainer = readableContentColor(tertiaryContainer),
        surfaceVariant = surfaceVariant, onSurfaceVariant = readableContentColor(surfaceVariant),
        outline = secondary, outlineVariant = secondaryContainer
    )
}
