package com.lin0721.linmusic.feature.local.domain

import kotlinx.serialization.Serializable

const val LOCAL_MUSIC_MIN_DURATION_MS = 30_000L

data class LocalImportProgress(
    val scanned: Int,
    val imported: Int,
    val skippedShort: Int,
    val isSaving: Boolean = false
)

@Serializable
data class LocalTrack(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long = 0L,
    val addedAt: Long,
    /** Root directory ids that contributed this track to the catalog. */
    val sourceDirectoryIds: Set<String> = emptySet()
)

@Serializable
data class LocalMusicDirectory(
    val id: String,
    val uri: String,
    val name: String,
    val addedAt: Long
)

data class LocalImportResult(
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
    val skippedShort: Int = 0
)

/** Pure catalog operations shared by persistence and unit tests. */
object LocalMusicCatalog {

    /** Merge in URI order while keeping the first record for every URI. */
    fun merge(existing: List<LocalTrack>, incoming: List<LocalTrack>): List<LocalTrack> {
        val seenUris = HashSet<String>(existing.size + incoming.size)
        return buildList(existing.size + incoming.size) {
            for (track in existing + incoming) {
                if (seenUris.add(track.uri)) {
                    add(track)
                } else {
                    val index = indexOfFirst { it.uri == track.uri }
                    if (index >= 0) {
                        val current = this[index]
                        this[index] = current.copy(
                            sourceDirectoryIds = current.sourceDirectoryIds + track.sourceDirectoryIds
                        )
                    }
                }
            }
        }
    }

    fun remove(existing: List<LocalTrack>, id: String): List<LocalTrack> =
        existing.filterNot { it.id == id }

    fun removeAll(existing: List<LocalTrack>, ids: Set<String>): List<LocalTrack> {
        if (ids.isEmpty()) return existing
        return existing.filterNot { it.id in ids }
    }

    /** Removing an imported directory removes every catalog record linked to that root. */
    fun removeDirectory(existing: List<LocalTrack>, directoryId: String): List<LocalTrack> =
        existing.filterNot { directoryId in it.sourceDirectoryIds }
}
