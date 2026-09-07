package com.lin0721.linmusic.feature.search.data

import com.lin0721.linmusic.core.auth.UserSessionTag
import com.lin0721.linmusic.core.player.data.SongUrlItem
import com.lin0721.linmusic.core.userplaylist.UserPlaylistRequest
import com.lin0721.linmusic.core.userplaylist.UserPlaylistResponse
import com.lin0721.linmusic.feature.create.data.PlaylistCreateRequest
import com.lin0721.linmusic.feature.create.data.PlaylistCreateResponse
import com.lin0721.linmusic.feature.playlist.data.PlaylistTracksManipulateRequest
import com.lin0721.linmusic.feature.playlist.data.PlaylistTracksManipulateResponse
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Tag

/** Every account operation is bound to the session that opened the selection sheet. */
interface SearchSongActionsApi {
    @POST("/eapi/user/playlist")
    suspend fun playlists(@Body body: UserPlaylistRequest, @Tag session: UserSessionTag): UserPlaylistResponse

    @POST("/eapi/playlist/manipulate/tracks")
    suspend fun add(@Body body: PlaylistTracksManipulateRequest, @Tag session: UserSessionTag): PlaylistTracksManipulateResponse

    @POST("/eapi/playlist/create")
    suspend fun create(@Body body: PlaylistCreateRequest, @Tag session: UserSessionTag): PlaylistCreateResponse

    @POST("/eapi/song/enhance/download/url")
    suspend fun download(@Body body: SongDownloadRequest, @Tag session: UserSessionTag): SongDownloadResponse
}

@Serializable
data class SongDownloadRequest(val id: Long, val br: Int = 320000)

@Serializable
data class SongDownloadResponse(val code: Int = 0, val data: SongUrlItem? = null)

internal fun SongDownloadResponse.requireFullDownload(songId: Long): SongUrlItem {
    val item = data
    require(code == 200 && item != null && item.id == songId && !item.url.isNullOrBlank() && item.freeTrialInfo == null) {
        "此歌曲暂不可下载，请检查账号的下载权限或版权限制"
    }
    return item
}
