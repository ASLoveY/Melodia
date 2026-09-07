package com.lin0721.linmusic.feature.library.ui

import com.lin0721.linmusic.core.model.ArtistInfo
import com.lin0721.linmusic.core.userplaylist.UserPlaylist
import com.lin0721.linmusic.feature.library.data.AlbumSubItem
import org.junit.Assert.*
import org.junit.Test

class UserLibraryItemsTest {
    @Test fun noCollectionsProducesAnEmptyLibraryWithoutVirtualPlaylists() {
        assertTrue(userLibraryItems(42, emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test fun ownedSubscribedAndFavoriteEntriesRemainWithoutAutomaticAdditions() {
        val items = userLibraryItems(
            42,
            listOf(
                UserPlaylist(id = 100, name = "我喜欢的音乐", userId = 42, specialType = 5),
                UserPlaylist(id = 101, name = "我创建的", userId = 42, specialType = 0),
                UserPlaylist(id = 102, name = "订阅歌单", userId = 99, specialType = 0)
            ),
            listOf(ArtistInfo(700, "关注歌手", "cover")),
            listOf(AlbumSubItem(id = 500, name = "收藏专辑"))
        )
        assertEquals(setOf("100", "101", "102", "700", "500"), items.map { it.id }.toSet())
        assertTrue(items.single { it.id == "100" }.isLikedSongs)
        assertTrue(items.single { it.id == "101" }.isOwnedByMe)
        assertFalse(items.single { it.id == "102" }.isOwnedByMe)
        assertFalse(items.any { it.id == "-2" })
    }

    @Test fun invalidPlaceholderIdsAreNotShown() {
        assertTrue(userLibraryItems(
            42,
            listOf(UserPlaylist(id = -2), UserPlaylist()),
            listOf(ArtistInfo(0, "", "")),
            listOf(AlbumSubItem())
        ).isEmpty())
    }
}
