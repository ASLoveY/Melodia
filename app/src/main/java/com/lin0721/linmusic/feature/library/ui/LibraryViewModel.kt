package com.lin0721.linmusic.feature.library.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.auth.UserProfile
import com.lin0721.linmusic.core.auth.UserSessionTag
import com.lin0721.linmusic.core.auth.SyncProfileAfterLoginUseCase
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.userartist.UserArtistRepository
import com.lin0721.linmusic.feature.create.data.CreateRepository
import com.lin0721.linmusic.core.userplaylist.UserPlaylistRepository
import com.lin0721.linmusic.feature.library.data.LibraryRepository
import com.lin0721.linmusic.core.player.PlayerManager
import com.lin0721.linmusic.core.network.ResourceProvider
import com.lin0721.linmusic.core.network.toUserMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "LibraryViewModel"

enum class LibraryItemType {
    PLAYLIST, ARTIST, ALBUM, MV
}

data class LibraryItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String,
    val type: LibraryItemType,
    val isPinned: Boolean = false,
    val updateTime: Long = 0,
    val trackCount: Int = 0,
    val playCount: Long = 0,
    val isLikedSongs: Boolean = false,
    val isOwnedByMe: Boolean = false,
    val ownerId: Long? = null
)

enum class LibraryFilter {
    PLAYLIST, ALBUM, ARTIST, MV
}

// 歌单二级筛选：仅在 LibraryFilter.PLAYLIST 生效，区分自建歌单与收藏他人歌单
enum class LibraryPlaylistOwnerFilter {
    MINE, OTHERS
}

enum class LibrarySortOrder {
    RECENTLY_PLAYED, CREATE_TIME, NAME
}

class LibraryViewModel(
    private val syncProfileAfterLoginUseCase: SyncProfileAfterLoginUseCase,
    private val createRepository: CreateRepository,
    private val libraryRepository: LibraryRepository,
    private val userPlaylistRepository: UserPlaylistRepository,
    private val userArtistRepository: UserArtistRepository,
    private val userPreferences: UserPreferences,
    val playerManager: PlayerManager,
    private val context: Context,
    private val resourceProvider: ResourceProvider
) : ViewModel() {

    private val sharedPrefs = context.getSharedPreferences("library_prefs", Context.MODE_PRIVATE)
    private val _pinnedIds = MutableStateFlow<Set<String>>(getPinnedIdsFromPrefs())

    private val _uiState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    val userProfile: StateFlow<UserProfile?> = userPreferences.userProfile.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null
    )

    // null 表示未筛选（默认展示全部），仅在用户点击某个分类胶囊后才收窄为对应类型
    private val _selectedFilter = MutableStateFlow<LibraryFilter?>(null)
    val selectedFilter: StateFlow<LibraryFilter?> = _selectedFilter.asStateFlow()

    // 歌单二级筛选状态，切换/清除主筛选时一并重置
    private val _selectedPlaylistOwnerFilter = MutableStateFlow<LibraryPlaylistOwnerFilter?>(null)
    val selectedPlaylistOwnerFilter: StateFlow<LibraryPlaylistOwnerFilter?> = _selectedPlaylistOwnerFilter.asStateFlow()

    private val _sortOrder = MutableStateFlow(LibrarySortOrder.RECENTLY_PLAYED)
    val sortOrder: StateFlow<LibrarySortOrder> = _sortOrder.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isGridView = MutableStateFlow(getGridViewFromPrefs())
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    private var activeUserId: Long? = null
    private var activeSessionTag: UserSessionTag? = null
    private var libraryLoadJob: Job? = null
    private var libraryLoadGeneration = 0L
    private val playlistRemoval = PlaylistRemovalCoordinator(
        scope = viewModelScope,
        currentSession = { activeSessionTag },
        remove = { id, kind, sessionTag ->
            val result = when (kind) {
                PlaylistRemovalKind.DELETE -> libraryRepository.deletePlaylist(id, sessionTag)
                PlaylistRemovalKind.UNSUBSCRIBE -> libraryRepository.unsubscribePlaylist(id, sessionTag)
            }.firstOrNull() ?: Result.failure(IllegalStateException("操作未完成，请重试"))
            result.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { Result.failure(IllegalStateException(it.toUserMessage(resourceProvider))) }
            )
        },
        onRemoved = ::removePlaylistFromLibrary
    )
    val playlistRemovalState = playlistRemoval.state

    init {
        viewModelScope.launch {
            userPreferences.userProfile.collect { profile ->
                activeSessionTag = userPreferences.currentSessionTag()?.takeIf { it.uid == profile?.uid }
                if (activeUserId != profile?.uid) {
                    activeUserId = profile?.uid
                    libraryLoadGeneration++
                    libraryLoadJob?.cancel()
                    playlistRemoval.reset()
                    _uiState.value = LibraryUiState.Loading
                }
                if (profile != null) {
                    loadLibraryData(profile)
                } else {
                    _uiState.value = LibraryUiState.Loading
                }
            }
        }
    }

    private fun getPinnedIdsFromPrefs(): Set<String> {
        return sharedPrefs.getStringSet("pinned_ids", emptySet()) ?: emptySet()
    }

    private fun savePinnedIdsToPrefs(ids: Set<String>) {
        sharedPrefs.edit().putStringSet("pinned_ids", ids).apply()
    }

    private fun getGridViewFromPrefs(): Boolean {
        // 读取视图切换记忆，默认列表视图 (false)
        return sharedPrefs.getBoolean("is_grid_view", false)
    }

    fun updateGridView(isGrid: Boolean) {
        _isGridView.value = isGrid
        sharedPrefs.edit().putBoolean("is_grid_view", isGrid).apply()
    }

    fun loadLibraryData(profileOverride: UserProfile? = null) {
        val profile = profileOverride ?: userProfile.value ?: return
        val isRefresh = _uiState.value is LibraryUiState.Success
        val generation = ++libraryLoadGeneration
        libraryLoadJob?.cancel()

        libraryLoadJob = viewModelScope.launch {
            if (!isRefresh) {
                _uiState.value = LibraryUiState.Loading
            }

            try {
                // 1. 并行获取歌单
                val playlistsDeferred = async {
                    val result = userPlaylistRepository.getUserPlaylists(profile.uid).firstOrNull()
                    result?.exceptionOrNull()?.let { AppLogger.w(TAG, "获取歌单列表失败", it) }
                    result?.getOrNull() ?: emptyList()
                }

                // 2. 并行获取歌手
                val artistsDeferred = async {
                    val result = userArtistRepository.getFavoriteArtists().firstOrNull()
                    result?.exceptionOrNull()?.let { AppLogger.w(TAG, "获取收藏歌手失败", it) }
                    result?.getOrNull() ?: emptyList()
                }

                // 3. 并行获取专辑
                val albumsDeferred = async {
                    val result = libraryRepository.getCollectedAlbums().firstOrNull()
                    result?.exceptionOrNull()?.let { AppLogger.w(TAG, "获取收藏专辑失败", it) }
                    result?.getOrNull() ?: emptyList()
                }

                // 4. 并行获取用户收藏统计数
                val subcountDeferred = async {
                    val result = libraryRepository.getUserSubcount().firstOrNull()
                    result?.exceptionOrNull()?.let { AppLogger.w(TAG, "获取收藏统计数失败", it) }
                    result?.getOrNull()
                }

                val playlists = playlistsDeferred.await()
                val artists = artistsDeferred.await()
                val albums = albumsDeferred.await()
                val subcount = subcountDeferred.await()
                if (generation != libraryLoadGeneration || activeUserId != profile.uid) return@launch

                val combinedItems = userLibraryItems(profile.uid, playlists, artists, albums)

                _uiState.update { state ->
                    LibraryUiState.Success(
                        allItems = combinedItems,
                        filteredItems = (state as? LibraryUiState.Success)?.filteredItems ?: emptyList(),
                        artistCount = subcount?.artistCount ?: artists.size,
                        playlistCount = subcount?.playlistCount ?: playlists.size,
                        albumCount = subcount?.albumCount ?: albums.size
                    )
                }

                applyFilterAndSort()

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != libraryLoadGeneration || activeUserId != profile.uid) return@launch
                AppLogger.e(TAG, "音乐库加载最终失败 isRefresh=$isRefresh", e)
                if (isRefresh) {
                    _toastEvent.emit(e.toUserMessage(resourceProvider))
                } else {
                    _uiState.value = LibraryUiState.Error(e.toUserMessage(resourceProvider))
                }
            }
        }
    }

    fun requestPlaylistRemoval(item: LibraryItem) {
        val current = (_uiState.value as? LibraryUiState.Success)?.allItems
            ?.firstOrNull { it.id == item.id && it.type == LibraryItemType.PLAYLIST } ?: return
        val id = current.id.toLongOrNull() ?: return
        val owner = current.ownerId ?: return
        playlistRemoval.request(PlaylistRemovalTarget(id, current.title, owner, current.isLikedSongs))
    }

    fun confirmPlaylistRemoval() = playlistRemoval.confirm()
    fun dismissPlaylistRemoval() = playlistRemoval.dismiss()

    private fun removePlaylistFromLibrary(id: Long) {
        // 取消旧加载，避免删除前发起的迟到请求把条目重新放回列表。
        libraryLoadGeneration++
        libraryLoadJob?.cancel()
        _pinnedIds.value = _pinnedIds.value - id.toString()
        savePinnedIdsToPrefs(_pinnedIds.value)
        _uiState.update { state ->
            if (state is LibraryUiState.Success) {
                val removed = state.allItems.any { it.type == LibraryItemType.PLAYLIST && it.id == id.toString() }
                state.copy(
                    allItems = state.allItems.filterNot { it.type == LibraryItemType.PLAYLIST && it.id == id.toString() },
                    playlistCount = (state.playlistCount - if (removed) 1 else 0).coerceAtLeast(0)
                )
            } else state
        }
        applyFilterAndSort()
    }

    private fun applyFilterAndSort() {
        val state = _uiState.value as? LibraryUiState.Success ?: return
        val pinned = _pinnedIds.value
        val filter = _selectedFilter.value
        val ownerFilter = _selectedPlaylistOwnerFilter.value
        val sort = _sortOrder.value
        val query = _searchQuery.value

        var list = state.allItems.map { item ->
            item.copy(isPinned = pinned.contains(item.id))
        }

        if (query.isNotBlank()) {
            list = list.filter {
                it.title.contains(query, ignoreCase = true) ||
                it.subtitle.contains(query, ignoreCase = true)
            }
        }

        list = when (filter) {
            null -> list
            LibraryFilter.PLAYLIST -> list.filter { it.type == LibraryItemType.PLAYLIST }
            LibraryFilter.ALBUM -> list.filter { it.type == LibraryItemType.ALBUM }
            LibraryFilter.ARTIST -> list.filter { it.type == LibraryItemType.ARTIST }
            LibraryFilter.MV -> list.filter { it.type == LibraryItemType.MV }
        }

        // 歌单二级筛选：我创建的 / 他人创建的（"我喜欢的音乐"视为我的）
        if (filter == LibraryFilter.PLAYLIST && ownerFilter != null) {
            list = list.filter { item ->
                val ownedByMe = item.isOwnedByMe || item.isLikedSongs
                if (ownerFilter == LibraryPlaylistOwnerFilter.MINE) ownedByMe else !ownedByMe
            }
        }

        val pinnedItems = list.filter { it.isPinned }
        val unpinnedItems = list.filter { !it.isPinned }

        val sortedUnpinned = when (sort) {
            LibrarySortOrder.RECENTLY_PLAYED -> {
                unpinnedItems.sortedByDescending { it.updateTime }
            }
            LibrarySortOrder.CREATE_TIME -> {
                unpinnedItems.sortedByDescending { it.updateTime }
            }
            LibrarySortOrder.NAME -> {
                unpinnedItems.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            }
        }

        val finalList = pinnedItems + sortedUnpinned

        val listWithoutSpecial = finalList.filterNot { it.isLikedSongs }
        val likedSongsItem = finalList.find { it.isLikedSongs }

        val finalListAdjusted = mutableListOf<LibraryItem>()
        if (likedSongsItem != null) {
            finalListAdjusted.add(likedSongsItem)
        }
        finalListAdjusted.addAll(listWithoutSpecial)

        _uiState.update { current ->
            if (current is LibraryUiState.Success) current.copy(filteredItems = finalListAdjusted) else current
        }
    }

    fun togglePin(itemId: String) {
        val currentPinned = _pinnedIds.value.toMutableSet()
        val willPin = !currentPinned.contains(itemId)
        if (willPin) {
            currentPinned.add(itemId)
        } else {
            currentPinned.remove(itemId)
        }
        _pinnedIds.value = currentPinned
        savePinnedIdsToPrefs(currentPinned)
        applyFilterAndSort()

        val title = (_uiState.value as? LibraryUiState.Success)?.allItems?.firstOrNull { it.id == itemId }?.title
        if (title != null) {
            viewModelScope.launch {
                _toastEvent.emit("已${if (willPin) "置顶" else "取消置顶"}: $title")
            }
        }
    }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            createRepository.createPlaylist(name).collect { result ->
                result.onSuccess {
                    loadLibraryData()
                    _toastEvent.emit("歌单创建成功！")
                }.onFailure { e ->
                    _toastEvent.emit(e.toUserMessage(resourceProvider))
                }
            }
        }
    }

    // 点击胶囊：再次点击已选中的胶囊会取消筛选，回到展示全部；切换主筛选时重置歌单二级筛选
    fun toggleFilter(filter: LibraryFilter) {
        _selectedFilter.value = if (_selectedFilter.value == filter) null else filter
        _selectedPlaylistOwnerFilter.value = null
        applyFilterAndSort()
    }

    fun clearFilter() {
        _selectedFilter.value = null
        _selectedPlaylistOwnerFilter.value = null
        applyFilterAndSort()
    }

    // 歌单二级筛选：再次点击已选中的胶囊会取消，恢复展示全部歌单
    fun togglePlaylistOwnerFilter(filter: LibraryPlaylistOwnerFilter) {
        _selectedPlaylistOwnerFilter.value = if (_selectedPlaylistOwnerFilter.value == filter) null else filter
        applyFilterAndSort()
    }

    fun updateSortOrder(order: LibrarySortOrder) {
        _sortOrder.value = order
        applyFilterAndSort()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        applyFilterAndSort()
    }

    fun handleLoginSuccess(cookies: String) {
        viewModelScope.launch {
            _toastEvent.emit("登录成功，正在同步乐库...")
            val profile = syncProfileAfterLoginUseCase(cookies) ?: return@launch
            loadLibraryData(profile)
        }
    }

    fun logout() {
        viewModelScope.launch {
            userPreferences.clearUserProfile()
        }
    }
}
