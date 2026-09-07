package com.lin0721.linmusic.core.player

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.serialization.Serializable

enum class PlayMode { LIST_LOOP, SINGLE_LOOP, SHUFFLE }

@Serializable
data class QueueItem(
    val songId: Long,
    val title: String,
    val artist: String,
    val coverUrl: String,
    val localUri: String? = null
) {
    val isLocal: Boolean get() = localUri != null

    // Remote items keep their historical song-id identity. Local items use their persisted URI;
    // songId is intentionally 0 for them and must never be used to distinguish two local files.
    val stableKey: String get() = localUri?.let { "local:$it" } ?: songId.toString()

    fun toMediaItem(url: String, playContext: String? = null): MediaItem {
        val bundle = Bundle().apply {
            putLong("songId", songId)
            localUri?.let { putString("localUri", it) }
            if (playContext != null) putString("playContext", playContext)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setArtworkUri(Uri.parse(coverUrl))
            .setExtras(bundle)
            .build()
        return MediaItem.Builder()
            .setUri(localUri ?: url)
            .setMediaId(stableKey)
            .setMediaMetadata(metadata)
            .build()
    }
}

/** True for content imported from the device rather than a remote numeric song id. */
val MediaItem.isLocalAudio: Boolean
    get() = mediaId.startsWith("local:")

/**
 * Returns the persisted local URI even when a MediaController has stripped localConfiguration
 * while crossing the service boundary. Metadata extras are the durable source of truth.
 */
internal fun MediaItem.localAudioUri(): String? {
    if (!isLocalAudio) return null
    return mediaMetadata.extras?.getString("localUri")?.takeIf { it.isNotBlank() }
        ?: localConfiguration?.uri?.toString()?.takeIf { it.isNotBlank() }
}
