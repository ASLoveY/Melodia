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

    @Test
    fun mergeUnionsDirectorySourcesForDuplicateTrack() {
        val merged = LocalMusicCatalog.merge(
            listOf(track("one", "content://song/1").copy(sourceDirectoryIds = setOf("dir-a"))),
            listOf(track("replacement", "content://song/1").copy(sourceDirectoryIds = setOf("dir-b")))
        )

        assertEquals(1, merged.size)
        assertEquals(setOf("dir-a", "dir-b"), merged.single().sourceDirectoryIds)
        assertEquals("one", merged.single().id)
    }

    @Test
    fun removeAllIsAtomicFromTheCatalogPerspective() {
        val tracks = listOf(
            track("one", "content://song/1"),
            track("two", "content://song/2"),
            track("three", "content://song/3")
        )

        assertEquals(
            listOf("one"),
            LocalMusicCatalog.removeAll(tracks, setOf("two", "three", "missing")).map { it.id }
        )
        assertEquals(tracks, LocalMusicCatalog.removeAll(tracks, emptySet()))
    }

    @Test
    fun removingDirectoryRemovesTracksEvenWhenTheyAlsoBelongToAnotherDirectory() {
        val tracks = listOf(
            track("one", "content://song/1").copy(sourceDirectoryIds = setOf("dir-a", "dir-b")),
            track("two", "content://song/2").copy(sourceDirectoryIds = setOf("dir-b")),
            track("three", "content://song/3").copy(sourceDirectoryIds = setOf("dir-c"))
        )

        assertEquals(
            listOf("three"),
            LocalMusicCatalog.removeDirectory(tracks, "dir-b").map { it.id }
        )
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
