package com.lin0721.linmusic.feature.local.domain

import android.net.Uri
import kotlinx.coroutines.flow.Flow

interface LocalMusicRepository {
    val tracks: Flow<List<LocalTrack>>

    val directories: Flow<List<LocalMusicDirectory>>

    suspend fun importUris(uris: List<Uri>): LocalImportResult

    /** Registers a completed app-owned download; no SAF grant is required. */
    suspend fun registerDownload(uri: Uri, song: com.lin0721.linmusic.core.model.Track)

    suspend fun importDirectory(
        uri: Uri,
        minDurationMs: Long = LOCAL_MUSIC_MIN_DURATION_MS,
        onProgress: (LocalImportProgress) -> Unit = {}
    ): LocalImportResult

    suspend fun remove(id: String)

    suspend fun removeAll(ids: Set<String>): Int

    suspend fun removeDirectory(id: String): Int
}
