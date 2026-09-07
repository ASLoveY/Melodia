package com.lin0721.linmusic.feature.library.ui

import com.lin0721.linmusic.core.model.ArtistInfo
import com.lin0721.linmusic.core.userplaylist.UserPlaylist
import com.lin0721.linmusic.feature.library.data.AlbumSubItem

/** Only entries from user-owned/subscribed collections belong in the online library. */
internal fun userLibraryItems(
    uid: Long,
    playlists: List<UserPlaylist>,
    artists: List<ArtistInfo>,
    albums: List<AlbumSubItem>
): List<LibraryItem> {
    val mappedPlaylists = playlists.filter { it.id > 0 }.mapIndexed { index, playlist ->
        val ownerId = playlist.creator?.userId?.takeIf { it > 0 } ?: playlist.userId.takeIf { it > 0 }
        LibraryItem(
            id = playlist.id.toString(), title = playlist.name,
            subtitle = "歌单 · ${playlist.creator?.nickname ?: ""}", coverUrl = playlist.coverImgUrl,
            type = LibraryItemType.PLAYLIST, updateTime = playlist.updateTime,
            trackCount = playlist.trackCount, playCount = playlist.playCount,
            isLikedSongs = playlist.specialType == 5 || (playlist.specialType == null && index == 0 && ownerId == uid),
            isOwnedByMe = ownerId == uid, ownerId = ownerId
        )
    }
    val mappedArtists = artists.filter { it.id > 0 }.map { artist ->
        LibraryItem(artist.id.toString(), artist.name, "歌手", artist.avatarUrl, LibraryItemType.ARTIST)
    }
    val mappedAlbums = albums.filter { it.id > 0 }.map { album ->
        LibraryItem(
            album.id.toString(), album.name, "专辑 · ${album.artists.joinToString(" • ") { it.name }}",
            album.picUrl, LibraryItemType.ALBUM, updateTime = album.subTime
        )
    }
    return mappedPlaylists + mappedArtists + mappedAlbums
}
