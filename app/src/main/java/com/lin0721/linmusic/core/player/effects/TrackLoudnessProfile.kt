package com.lin0721.linmusic.core.player.effects

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TrackLoudnessProfile(val sourceKey: String, val integratedLufs: Double, val durationMs: Long, val version: Int = 1)

class LoudnessProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("loudness_profiles_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    fun read(key: String): TrackLoudnessProfile? = runCatching {
        prefs.getString(key, null)?.let { json.decodeFromString<TrackLoudnessProfile>(it) }
            ?.takeIf { it.version == 1 && it.sourceKey == key && it.integratedLufs.isFinite() }
    }.getOrNull()
    fun save(profile: TrackLoudnessProfile) {
        prefs.edit().putString(profile.sourceKey, json.encodeToString(profile)).apply()
    }
}
