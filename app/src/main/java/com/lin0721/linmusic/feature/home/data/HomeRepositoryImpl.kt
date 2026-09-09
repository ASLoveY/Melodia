package com.lin0721.linmusic.feature.home.data

import com.lin0721.linmusic.core.contentfilter.ContentFilter
import com.lin0721.linmusic.core.network.apiFlow
import com.lin0721.linmusic.feature.home.domain.HomeBlockPage
import com.lin0721.linmusic.feature.home.domain.ToplistInfo
import com.lin0721.linmusic.feature.home.domain.toHomeBlockPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.auth.SessionChangedException
import com.lin0721.linmusic.core.network.AppError

class HomeRepositoryImpl(
    private val apiService: HomeApi,
    private val contentFilter: ContentFilter,
    private val userPreferences: UserPreferences
) : HomeRepository {

    override fun getHomeBlockPage(refresh: Boolean, cursor: String): Flow<Result<HomeBlockPage>> = apiFlow(
        request = { apiService.getHomeBlockPage(HomeBlockPageRequest(refresh = refresh, cursor = cursor)) },
        isSuccess = { it.isSuccess && it.data != null },
        code = { it.code },
        transform = { it.data!!.toHomeBlockPage() }
    )

    override fun getPersonalizedPlaylists(): Flow<Result<PersonalizedData>> = apiFlow(
        request = { apiService.getPersonalizedPlaylists() },
        isSuccess = { it.isSuccess },
        code = { it.code },
        transform = { PersonalizedData(playlists = it.result) }
    )

    override fun getToplistDetail(): Flow<Result<List<ToplistInfo>>> = apiFlow(
        request = { apiService.getToplistDetail() },
        isSuccess = { it.code == 200 },
        code = { it.code },
        transform = { response ->
            response.list
                // 过滤封面图为空的无效榜单条目
                .filter { it.coverImgUrl.isNotBlank() && it.name.isNotBlank() }
                .map { dto ->
                    ToplistInfo(
                        id = dto.id,
                        name = dto.name,
                        coverUrl = "${dto.coverImgUrl}?param=300y300",
                        updateDesc = dto.updateFrequency,
                        topSongs = dto.tracks?.map { "${it.first} - ${it.second}" } ?: emptyList()
                    )
                }
        }
    )

    override fun getDailyRecommendSongs(): Flow<Result<List<DailySong>>> = flow {
        val session = userPreferences.currentSessionTag()
        if (session == null) { emit(Result.failure(AppError.Unauthorized)); return@flow }
        emitAll(apiFlow(
        request = {
            resolveDailyRecommendations(
                primary = { apiService.getDailyRecommendSongs(sessionTag = session) },
                legacy = { apiService.getLegacyDailyRecommendSongs(sessionTag = session) }
            )
        },
        isSuccess = { it.isSuccess && it.songs != null },
        code = { it.code },
        msg = { it.message ?: "每日推荐暂未返回歌曲列表，请稍后重试" },
        transform = {
            if (userPreferences.currentSessionTag() != session) throw SessionChangedException()
            contentFilter.filterBlockedArtists(it.songs!!.filter { song -> song.id > 0 }) { song -> song.ar.map { a -> a.id } }
        }
    )) }

    override fun getHistoryRecommendDates(): Flow<Result<List<String>>> = apiFlow(
        request = { apiService.getHistoryRecommendDates() },
        isSuccess = { it.code == 200 && it.data != null },
        code = { it.code },
        transform = { it.data!!.list }
    )

    override fun getHistoryRecommendDetail(date: String): Flow<Result<List<DailySong>>> = apiFlow(
        request = { apiService.getHistoryRecommendDetail(HistoryDetailRequest(date = date)) },
        isSuccess = { it.code == 200 && it.data != null },
        code = { it.code },
        transform = { contentFilter.filterBlockedArtists(it.data!!.dailySongs) { song -> song.ar.map { a -> a.id } } }
    )
}

/** Retry only an incomplete successful payload; never conceal login or service errors. */
internal suspend fun resolveDailyRecommendations(
    primary: suspend () -> DailyRecommendSongsResponse,
    legacy: suspend () -> DailyRecommendSongsResponse
): DailyRecommendSongsResponse {
    val response = primary()
    return if (response.isSuccess && response.songs == null) legacy() else response
}
