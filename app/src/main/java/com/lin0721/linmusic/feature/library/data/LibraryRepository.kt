package com.lin0721.linmusic.feature.library.data

import com.lin0721.linmusic.core.model.Track
import kotlinx.coroutines.flow.Flow

// 音乐库数据仓储（library 业务域）
interface LibraryRepository {

    // 获取听歌排行
    fun getUserRecord(uid: Long, type: Int): Flow<Result<List<Track>>>

    // 获取收藏专辑
    fun getCollectedAlbums(limit: Int = 1000): Flow<Result<List<AlbumSubItem>>>

    // 获取各分类收藏数
    fun getUserSubcount(): Flow<Result<UserSubcountResponse>>

    // 删除当前用户创建的歌单
    fun deletePlaylist(id: Long): Flow<Result<Unit>>

    // 取消收藏歌单
    fun unsubscribePlaylist(id: Long): Flow<Result<Unit>>
}
