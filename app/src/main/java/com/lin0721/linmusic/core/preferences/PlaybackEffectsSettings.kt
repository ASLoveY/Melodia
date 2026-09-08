package com.lin0721.linmusic.core.preferences

data class PlaybackEffectsSettings(
    val crossfadeEnabled: Boolean = true,
    val crossfadeSeconds: Int = 3,
    val normalizationEnabled: Boolean = true
) {
    val durationMs: Long get() = crossfadeSeconds.coerceIn(1, 12) * 1000L
}

data class BackgroundSettings(val imagePath: String? = null, val transparency: Int = 70)
