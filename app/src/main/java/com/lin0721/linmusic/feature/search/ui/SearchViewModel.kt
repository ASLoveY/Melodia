package com.lin0721.linmusic.feature.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.auth.UserProfile
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.network.ResourceProvider
import com.lin0721.linmusic.core.network.toUserMessage
import com.lin0721.linmusic.core.player.PlayerManager
import com.lin0721.linmusic.core.player.QueueItem
import com.lin0721.linmusic.feature.search.data.SearchHistoryPreferences
import com.lin0721.linmusic.feature.search.data.SearchRepository
import com.lin0721.linmusic.feature.search.domain.SearchResultItem
import com.lin0721.linmusic.feature.search.domain.SearchType
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SearchViewModel(
    private val repository: SearchRepository,
    private val historyPreferences: SearchHistoryPreferences,
    val playerManager: PlayerManager,
    userPreferences: UserPreferences,
    private val resourceProvider: ResourceProvider
) : ViewModel() {

    val userProfile: StateFlow<UserProfile?> = userPreferences.userProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val history: StateFlow<List<String>> = historyPreferences.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _discoveryState = MutableStateFlow<DiscoveryUiState>(DiscoveryUiState.Loading)
    val discoveryState: StateFlow<DiscoveryUiState> = _discoveryState.asStateFlow()

    private val _inputState = MutableStateFlow(SearchInputState())
    val inputState: StateFlow<SearchInputState> = _inputState.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    private val _selectedType = MutableStateFlow(SearchType.SONG)
    val selectedType: StateFlow<SearchType> = _selectedType.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    private val resultsCoordinator = SearchResultsCoordinator(
        repository = repository,
        scope = viewModelScope,
        errorMessage = { it.toUserMessage(resourceProvider) },
        onToast = { _toastEvent.emit(it) }
    )
    val resultsByType: Map<SearchType, StateFlow<SearchResultsUiState>> = resultsCoordinator.resultsByType

    // 输入防抖任务与联想词请求各自可取消；代数同时防止不可取消的 Repository 回调迟到覆盖新输入。
    private var searchScheduleJob: Job? = null
    private var suggestJob: Job? = null
    private var queryGeneration = 0L

    init {
        loadDiscoveryData()
    }

    private fun loadDiscoveryData() {
        viewModelScope.launch {
            _discoveryState.value = DiscoveryUiState.Loading

            val keywordDeferred = async { repository.getDefaultSearchKeyword().firstOrNull() }
            val hotSearchDeferred = async { repository.getHotSearches().firstOrNull() }
            val tagsDeferred = async { repository.getPlaylistTags().firstOrNull() }

            val keywordResult = keywordDeferred.await()
            val hotSearchResult = hotSearchDeferred.await()
            val tagsResult = tagsDeferred.await()

            val failures = listOfNotNull(keywordResult, hotSearchResult, tagsResult).mapNotNull { it.exceptionOrNull() }
            val allFailed = keywordResult?.getOrNull() == null &&
                hotSearchResult?.getOrNull() == null &&
                tagsResult?.getOrNull() == null

            if (allFailed && failures.isNotEmpty()) {
                _discoveryState.value = DiscoveryUiState.Error(failures.first().toUserMessage(resourceProvider))
                return@launch
            }

            _discoveryState.value = DiscoveryUiState.Success(
                defaultKeyword = keywordResult?.getOrNull() ?: "搜索你想听的",
                hotSearches = hotSearchResult?.getOrNull() ?: emptyList(),
                playlistTags = tagsResult?.getOrNull() ?: emptyList()
            )
            failures.firstOrNull()?.let { _toastEvent.emit(it.toUserMessage(resourceProvider)) }
        }
    }

    // 发现页加载失败时的重试入口，UI 层错误态按钮调用
    fun retryDiscovery() {
        loadDiscoveryData()
    }

    // 搜索结果加载失败时的重试入口：重跑当前 Tab 当前关键词，不重复写历史
    fun retrySearch() {
        val type = _selectedType.value
        val keyword = _inputState.value.query
        if (keyword.isBlank()) return
        searchScheduleJob?.cancel()
        resultsCoordinator.search(keyword, type, isLoadMore = false)
    }

    fun activateSearch() {
        _isSearchActive.value = true
    }

    fun deactivateSearch() {
        _isSearchActive.value = false
        resetSearchState()
    }

    private fun resetSearchState() {
        queryGeneration += 1
        searchScheduleJob?.cancel()
        suggestJob?.cancel()
        searchScheduleJob = null
        suggestJob = null
        _inputState.value = SearchInputState()
        resultsCoordinator.clear()
    }

    fun updateQuery(newQuery: String) {
        val generation = ++queryGeneration
        searchScheduleJob?.cancel()
        suggestJob?.cancel()
        searchScheduleJob = null
        suggestJob = null

        // “关键词 + 分类”共同决定结果；输入每次变化都让所有分类缓存立即失效，避免切 Tab 看到旧词结果。
        resultsCoordinator.clear()
        _inputState.value = SearchInputState(query = newQuery)

        if (newQuery.isBlank()) {
            return
        }

        _inputState.value = _inputState.value.copy(isSuggesting = true)
        suggestJob = viewModelScope.launch {
            delay(300)
            repository.getSuggestions(newQuery).firstOrNull()?.onSuccess { suggestions ->
                if (generation == queryGeneration && _inputState.value.query == newQuery) {
                    _inputState.value = _inputState.value.copy(suggestions = suggestions)
                }
            }
        }

        searchScheduleJob = viewModelScope.launch {
            delay(400)
            if (generation == queryGeneration && _inputState.value.query == newQuery) {
                resultsCoordinator.search(newQuery, _selectedType.value, isLoadMore = false)
            }
        }
    }

    // 明确提交搜索：选中联想词 / 历史词 / 热搜词，写入历史并立即执行（不走防抖）
    // 热搜/精品歌单等入口是在发现页（isSearchActive 尚为 false）触发的，必须一并置为激活态，
    // 否则 query 已经写入但 UI 判断展示结果区的条件不满足，页面停留在发现页看起来像没反应
    fun searchWithKeyword(keyword: String) {
        queryGeneration += 1
        searchScheduleJob?.cancel()
        suggestJob?.cancel()
        searchScheduleJob = null
        suggestJob = null
        resultsCoordinator.clear()
        _isSearchActive.value = true
        _inputState.value = SearchInputState(query = keyword)
        if (keyword.isBlank()) return
        viewModelScope.launch { historyPreferences.addKeyword(keyword) }

        resultsCoordinator.search(keyword, _selectedType.value, isLoadMore = false)
    }

    fun selectType(type: SearchType) {
        if (_selectedType.value == type) return
        _selectedType.value = type
        val query = _inputState.value.query
        if (query.isNotBlank() && resultsByType.getValue(type).value is SearchResultsUiState.Idle) {
            resultsCoordinator.search(query, type, isLoadMore = false)
        }
    }

    fun loadMore() {
        val type = _selectedType.value
        val keyword = _inputState.value.query
        if (keyword.isBlank()) return
        resultsCoordinator.search(keyword, type, isLoadMore = true)
    }

    fun clearHistory() {
        viewModelScope.launch { historyPreferences.clear() }
    }

    fun playSong(track: Track) {
        val state = resultsByType.getValue(SearchType.SONG).value
        if (state !is SearchResultsUiState.Success) return
        val tracks = state.items.filterIsInstance<SearchResultItem.SongItem>().map { it.track }
        val queueItems = tracks.map { t ->
            QueueItem(t.id, t.name, t.ar.joinToString { it.name }, t.al.picUrl)
        }
        val startIndex = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        playerManager.playQueue(queueItems, startIndex, "搜索")
    }
}
