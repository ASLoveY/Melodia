package com.lin0721.linmusic.feature.local.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMusicCatalogTest {

    @Test
    fun mergeDeduplicatesByUriAndKeepsFirstRecord() {
        val existing = listOf(track(id = "old", uri = "content://song/1"))
        val incoming = listOf(
            track(id = "same", uri = "content://song/1", title = "new metadata"),
            track(id = "two", uri = "content://song/2")
        )

        val merged = LocalMusicCatalog.merge(existing, incoming)

        assertEquals(listOf("old", "two"), merged.map { it.id })
        assertEquals("old", merged.first().id)
    }

    @Test
    fun removeOnlyRemovesCatalogRecord() {
        val tracks = listOf(
            track(id = "one", uri = "content://song/1"),
            track(id = "two", uri = "content://song/2")
        )

        assertEquals(listOf("two"), LocalMusicCatalog.remove(tracks, "one").map { it.id })
        assertEquals(tracks, LocalMusicCatalog.remove(tracks, "missing"))
    }

    private fun track(
        id: String,
        uri: String,
        title: String = id
    ) = LocalTrack(
        id = id,
        uri = uri,
        title = title,
        artist = "artist",
        album = "album",
        addedAt = 1L
    )
}
