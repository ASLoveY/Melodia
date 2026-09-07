package com.lin0721.linmusic.feature.local.domain

import android.net.Uri
import kotlinx.coroutines.flow.Flow

interface LocalMusicRepository {
    val tracks: Flow<List<LocalTrack>>

    suspend fun importUris(uris: List<Uri>): LocalImportResult

    suspend fun importDirectory(
        uri: Uri,
        minDurationMs: Long = LOCAL_MUSIC_MIN_DURATION_MS,
        onProgress: (LocalImportProgress) -> Unit = {}
    ): LocalImportResult

    suspend fun remove(id: String)
}
