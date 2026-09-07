package com.lin0721.linmusic.feature.local.domain

import kotlinx.serialization.Serializable

@Serializable
data class LocalTrack(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long = 0L,
    val addedAt: Long
)

data class LocalImportResult(
    val imported: Int,
    val duplicates: Int,
    val failed: Int
)

/** Pure catalog operations shared by persistence and unit tests. */
object LocalMusicCatalog {

    /** Merge in URI order while keeping the first record for every URI. */
    fun merge(existing: List<LocalTrack>, incoming: List<LocalTrack>): List<LocalTrack> {
        val seenUris = HashSet<String>(existing.size + incoming.size)
        return buildList(existing.size + incoming.size) {
            for (track in existing + incoming) {
                if (seenUris.add(track.uri)) add(track)
            }
        }
    }

    fun remove(existing: List<LocalTrack>, id: String): List<LocalTrack> =
        existing.filterNot { it.id == id }
}
