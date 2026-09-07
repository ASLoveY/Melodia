package com.lin0721.linmusic.feature.search.ui

import com.lin0721.linmusic.core.model.Album
import com.lin0721.linmusic.core.model.Artist
import com.lin0721.linmusic.core.model.PlaylistDetail
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.feature.search.data.SearchRepository
import com.lin0721.linmusic.feature.search.domain.HotSearch
import com.lin0721.linmusic.feature.search.domain.PlaylistCategoryPage
import com.lin0721.linmusic.feature.search.domain.PlaylistTag
import com.lin0721.linmusic.feature.search.domain.SearchPageResult
import com.lin0721.linmusic.feature.search.domain.SearchResultItem
import com.lin0721.linmusic.feature.search.domain.SearchSuggestion
import com.lin0721.linmusic.feature.search.domain.SearchType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultsCoordinatorTest {

    @Test
    fun `关键词变更时所有分类旧结果都被清空`() = runTest {
        val repository = ControlledSearchRepository()
        val coordinator = coordinator(repository, this)

        coordinator.search("旧词", SearchType.SONG, isLoadMore = false)
        runCurrent()
        repository.completeLast(pageFor(SearchType.SONG, id = 1))
        runCurrent()

        coordinator.search("旧词", SearchType.ALBUM, isLoadMore = false)
        runCurrent()
        repository.completeLast(pageFor(SearchType.ALBUM, id = 2))
        runCurrent()

        assertTrue(coordinator.resultsByType.getValue(SearchType.SONG).value is SearchResultsUiState.Success)
        assertTrue(coordinator.resultsByType.getValue(SearchType.ALBUM).value is SearchResultsUiState.Success)

        // SearchViewModel 在关键词变化时调用 clear；所有分类都必须失效，而不是只清理当前 Tab。
        coordinator.clear()

        SearchType.entries.forEach { type ->
            assertEquals(SearchResultsUiState.Idle, coordinator.resultsByType.getValue(type).value)
        }
    }

    @Test
    fun `切换分类会恢复被取消的初次加载状态`() = runTest {
        val repository = ControlledSearchRepository()
        val coordinator = coordinator(repository, this)

        coordinator.search("A", SearchType.SONG, isLoadMore = false)
        runCurrent()
        assertEquals(SearchResultsUiState.Loading, coordinator.resultsByType.getValue(SearchType.SONG).value)

        coordinator.search("A", SearchType.ALBUM, isLoadMore = false)
        runCurrent()

        // SONG 的请求被取消后再切回来，应允许重新发起，而不能因为残留 Loading 被跳过。
        assertEquals(SearchResultsUiState.Idle, coordinator.resultsByType.getValue(SearchType.SONG).value)
        coordinator.search("A", SearchType.SONG, isLoadMore = false)
        runCurrent()
        assertEquals(2, repository.requests.count { it.type == SearchType.SONG })

        repository.completeLast(pageFor(SearchType.SONG, id = 3))
        runCurrent()
        assertEquals(listOf(3L), songIds(coordinator.resultsByType.getValue(SearchType.SONG).value))
    }

    @Test
    fun `同分类替换未完成请求后再切换回来不会残留 Loading`() = runTest {
        val repository = ControlledSearchRepository()
        val coordinator = coordinator(repository, this)

        coordinator.search("A", SearchType.SONG, isLoadMore = false)
        runCurrent()
        coordinator.search("A", SearchType.SONG, isLoadMore = false)
        runCurrent()

        // 第二个 SONG 请求随后被切到 ALBUM；其前一请求的 Loading 不能成为回滚快照。
        coordinator.search("A", SearchType.ALBUM, isLoadMore = false)
        runCurrent()
        assertEquals(SearchResultsUiState.Idle, coordinator.resultsByType.getValue(SearchType.SONG).value)

        coordinator.search("A", SearchType.SONG, isLoadMore = false)
        runCurrent()
        assertEquals(3, repository.requests.count { it.type == SearchType.SONG })
        repository.completeLast(pageFor(SearchType.SONG, id = 4))
        runCurrent()
        assertEquals(listOf(4L), songIds(coordinator.resultsByType.getValue(SearchType.SONG).value))
    }

    @Test
    fun `切换分类会恢复被取消的加载更多并可继续分页`() = runTest {
        val repository = ControlledSearchRepository()
        val coordinator = coordinator(repository, this)

        coordinator.search("A", SearchType.SONG, isLoadMore = false)
        runCurrent()
        repository.completeLast(pageFor(SearchType.SONG, id = 1, hasMore = true, rawFetchedCount = 1))
        runCurrent()

        coordinator.search("A", SearchType.SONG, isLoadMore = true)
        runCurrent()
        coordinator.search("A", SearchType.ALBUM, isLoadMore = false)
        runCurrent()

        val restored = coordinator.resultsByType.getValue(SearchType.SONG).value as SearchResultsUiState.Success
        assertEquals(listOf(1L), songIds(restored))
        assertTrue(!restored.isLoadingMore)

        // 切回 SONG 后保留上一页，仍可继续发起 offset=1 的加载更多。
        coordinator.search("A", SearchType.SONG, isLoadMore = true)
        runCurrent()
        repository.completeLast(pageFor(SearchType.SONG, id = 2, hasMore = false, rawFetchedCount = 1))
        runCurrent()

        assertEquals(listOf(0, 1, 1), repository.requests.filter { it.type == SearchType.SONG }.map { it.offset })
        assertEquals(listOf(1L, 2L), songIds(coordinator.resultsByType.getValue(SearchType.SONG).value))
    }

    @Test
    fun `分页结果刷新被取消后加载更多仍使用原 offset`() = runTest {
        val repository = ControlledSearchRepository()
        val coordinator = coordinator(repository, this)

        coordinator.search("A", SearchType.SONG, isLoadMore = false)
        runCurrent()
        repository.completeLast(pageFor(SearchType.SONG, id = 1, hasMore = true, rawFetchedCount = 1))
        runCurrent()

        // 初次刷新会把 offset 临时置零；取消时恢复原结果也必须恢复 offset=1。
        coordinator.search("A", SearchType.SONG, isLoadMore = false)
        runCurrent()
        coordinator.search("A", SearchType.ALBUM, isLoadMore = false)
        runCurrent()

        val restored = coordinator.resultsByType.getValue(SearchType.SONG).value as SearchResultsUiState.Success
        assertEquals(listOf(1L), songIds(restored))
        coordinator.search("A", SearchType.SONG, isLoadMore = true)
        runCurrent()

        assertEquals(1, repository.requests.last().offset)
        repository.completeLast(pageFor(SearchType.SONG, id = 2, hasMore = false, rawFetchedCount = 1))
        runCurrent()
        assertEquals(listOf(1L, 2L), songIds(coordinator.resultsByType.getValue(SearchType.SONG).value))
    }

    @Test
    fun `旧关键词迟到结果不能覆盖新关键词`() = runTest {
        val repository = ControlledSearchRepository()
        val coordinator = coordinator(repository, this)

        coordinator.search("A", SearchType.SONG, isLoadMore = false)
        runCurrent()
        val oldRequest = repository.requests.last()

        coordinator.search("B", SearchType.SONG, isLoadMore = false)
        runCurrent()
        val newRequest = repository.requests.last()

        // 即便旧请求的底层回调在取消后才返回，也不能污染当前查询。
        oldRequest.result.complete(Result.success(pageFor(SearchType.SONG, id = 10)))
        runCurrent()
        assertEquals(SearchResultsUiState.Loading, coordinator.resultsByType.getValue(SearchType.SONG).value)

        newRequest.result.complete(Result.success(pageFor(SearchType.SONG, id = 20)))
        runCurrent()
        assertEquals(listOf(20L), songIds(coordinator.resultsByType.getValue(SearchType.SONG).value))
    }

    @Test
    fun `加载更多失败会保留原结果并允许按原 offset 重试`() = runTest {
        val repository = ControlledSearchRepository()
        val coordinator = coordinator(repository, this)

        coordinator.search("q", SearchType.SONG, isLoadMore = false)
        runCurrent()
        repository.completeLast(pageFor(SearchType.SONG, id = 1, hasMore = true, rawFetchedCount = 1))
        runCurrent()

        coordinator.search("q", SearchType.SONG, isLoadMore = true)
        runCurrent()
        assertTrue((coordinator.resultsByType.getValue(SearchType.SONG).value as SearchResultsUiState.Success).isLoadingMore)
        repository.completeLastFailure(IllegalStateException("temporary"))
        runCurrent()

        val afterFailure = coordinator.resultsByType.getValue(SearchType.SONG).value as SearchResultsUiState.Success
        assertEquals(listOf(1L), songIds(afterFailure))
        assertTrue(afterFailure.hasMore)
        assertTrue(!afterFailure.isLoadingMore)

        // 失败不推进 offset；重试仍请求第二页的同一 offset。
        coordinator.search("q", SearchType.SONG, isLoadMore = true)
        runCurrent()
        repository.completeLast(pageFor(SearchType.SONG, id = 2, hasMore = false, rawFetchedCount = 1))
        runCurrent()

        assertEquals(listOf(0, 1, 1), repository.requests.filter { it.type == SearchType.SONG }.map { it.offset })
        assertEquals(listOf(1L, 2L), songIds(coordinator.resultsByType.getValue(SearchType.SONG).value))
    }

    private fun coordinator(repository: ControlledSearchRepository, scope: CoroutineScope) = SearchResultsCoordinator(
        repository = repository,
        scope = scope,
        errorMessage = { it.message.orEmpty() },
        onToast = { repository.toastMessages += it }
    )

    private fun pageFor(
        type: SearchType,
        id: Long,
        hasMore: Boolean = false,
        rawFetchedCount: Int = 1
    ): SearchPageResult {
        val item = when (type) {
            SearchType.SONG -> SearchResultItem.SongItem(Track(id = id, name = "song-$id"))
            SearchType.ALBUM -> SearchResultItem.AlbumItem(Album(id = id, name = "album-$id"))
            SearchType.ARTIST -> SearchResultItem.ArtistItem(Artist(id = id, name = "artist-$id"))
            SearchType.PLAYLIST -> SearchResultItem.PlaylistItem(PlaylistDetail(id = id, name = "playlist-$id"))
        }
        return SearchPageResult(listOf(item), totalCount = 2, hasMore = hasMore, rawFetchedCount = rawFetchedCount)
    }

    private fun songIds(state: SearchResultsUiState): List<Long> =
        (state as? SearchResultsUiState.Success)?.items.orEmpty()
            .mapNotNull { (it as? SearchResultItem.SongItem)?.track?.id }
}

private class ControlledSearchRepository : SearchRepository {

    data class PendingRequest(
        val keyword: String,
        val type: SearchType,
        val offset: Int,
        val result: CompletableDeferred<Result<SearchPageResult>> = CompletableDeferred()
    )

    val requests = mutableListOf<PendingRequest>()
    val toastMessages = mutableListOf<String>()

    override fun search(keyword: String, type: SearchType, offset: Int, limit: Int): Flow<Result<SearchPageResult>> = flow {
        val request = PendingRequest(keyword, type, offset)
        requests += request
        emit(request.result.await())
    }

    fun completeLast(page: SearchPageResult) {
        requests.last().result.complete(Result.success(page))
    }

    fun completeLastFailure(error: Throwable) {
        requests.last().result.complete(Result.failure(error))
    }

    override fun getDefaultSearchKeyword(): Flow<Result<String>> = flowOf(Result.success(""))

    override fun getHotSearches(): Flow<Result<List<HotSearch>>> = flowOf(Result.success(emptyList()))

    override fun getPlaylistTags(): Flow<Result<List<PlaylistTag>>> = flowOf(Result.success(emptyList()))

    override fun getSuggestions(keyword: String): Flow<Result<List<SearchSuggestion>>> =
        flowOf(Result.success(emptyList()))

    override fun getPlaylistsByCategory(category: String, cursor: Long, limit: Int): Flow<Result<PlaylistCategoryPage>> =
        flowOf(Result.success(PlaylistCategoryPage(emptyList(), hasMore = false, nextCursor = 0, total = 0)))
}
