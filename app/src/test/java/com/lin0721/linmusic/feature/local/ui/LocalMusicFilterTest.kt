package com.lin0721.linmusic.feature.local.ui

import com.lin0721.linmusic.feature.local.domain.LocalTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMusicFilterTest {

    @Test
    fun filterMatchesTitleArtistAlbumAndSortsByRequestedOrder() {
        val tracks = listOf(
            track("one", "Morning", "Alpha", "Blue", 10L),
            track("two", "Evening", "Beta", "Morning", 30L),
            track("three", "Zed", "Alpha", "Red", 20L)
        )

        assertEquals(listOf("two", "one"), filterLocalTracks(tracks, "morning", LocalMusicSort.TITLE).map { it.id })
        assertEquals(listOf("two", "three", "one"), filterLocalTracks(tracks, "", LocalMusicSort.RECENT).map { it.id })
        assertEquals(listOf("one", "three", "two"), filterLocalTracks(tracks, "", LocalMusicSort.ARTIST).map { it.id })
    }

    private fun track(id: String, title: String, artist: String, album: String, addedAt: Long) =
        LocalTrack(id, "content://local/$id", title, artist, album, addedAt = addedAt)
}
