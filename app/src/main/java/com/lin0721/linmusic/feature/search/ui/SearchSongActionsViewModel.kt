package com.lin0721.linmusic.feature.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin0721.linmusic.core.auth.SessionChangedException
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.auth.UserSessionTag
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.player.PlayerManager
import com.lin0721.linmusic.core.player.QueueItem
import com.lin0721.linmusic.core.userplaylist.UserPlaylist
import com.lin0721.linmusic.core.userplaylist.UserPlaylistRequest
import com.lin0721.linmusic.feature.create.data.PlaylistCreateRequest
import com.lin0721.linmusic.feature.playlist.data.PlaylistTracksManipulateRequest
import com.lin0721.linmusic.feature.playlist.domain.SongCollectDelegate
import com.lin0721.linmusic.feature.search.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class SongPlaylistSelection(
    val song: Track, val session: UserSessionTag, val items: List<UserPlaylist> = emptyList(),
    val loading: Boolean = true, val saving: Boolean = false, val error: String? = null
)
data class SongDownloadState(val song: Track, val progress: Int? = null, val error: String? = null, val done: Boolean = false)

class SearchSongActionsViewModel(
    private val api: SearchSongActionsApi,
    private val preferences: UserPreferences,
    private val player: PlayerManager,
    private val downloads: SongDownloadRepository
) : ViewModel() {
    private val _selection = MutableStateFlow<SongPlaylistSelection?>(null)
    val selection = _selection.asStateFlow()
    private val _download = MutableStateFlow<SongDownloadState?>(null)
    val download = _download.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()
    private var selectionJob: Job? = null
    private var downloadJob: Job? = null

    fun enqueue(song: Track, next: Boolean) {
        player.enqueue(QueueItem(song.id, song.name, song.ar.joinToString(" / ") { it.name }, song.al.picUrl), next)
        _messages.tryEmit(if (next) "已设为下一首播放" else "已加入队列末尾")
    }

    fun choosePlaylist(song: Track) {
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch {
            val session = preferences.currentSessionTag() ?: run { _messages.emit("请先登录后添加到歌单"); return@launch }
            _selection.value = SongPlaylistSelection(song, session)
            loadPlaylists()
        }
    }

    fun retryPlaylists() { selectionJob = viewModelScope.launch { loadPlaylists() } }

    private suspend fun loadPlaylists() {
        val selected = _selection.value ?: return
        _selection.value = selected.copy(loading = true, error = null)
        try {
            checkSession(selected.session)
            val response = api.playlists(UserPlaylistRequest(selected.session.uid), selected.session)
            require(response.code == 200) { "无法读取歌单（${response.code}），请重试" }
            checkSession(selected.session)
            _selection.value = selected.copy(loading = false, items = response.playlist.filter {
                it.id > 0 && it.userId == selected.session.uid && it.specialType != 5
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            _selection.value = selected.copy(loading = false, error = error.userMessage())
        }
    }

    fun addToPlaylist(id: Long? = null, name: String? = null) {
        val selected = _selection.value?.takeUnless { it.loading || it.saving } ?: return
        if (id != null && selected.items.none { it.id == id }) return
        if (id == null && name.isNullOrBlank()) return
        _selection.value = selected.copy(saving = true, error = null)
        selectionJob = viewModelScope.launch {
            var createdId: Long? = null
            try {
                checkSession(selected.session)
                val target = id ?: api.create(PlaylistCreateRequest(name!!.trim()), selected.session).let {
                    require(it.code == 200) { "创建歌单失败（${it.code}）" }
                    (it.playlist?.id ?: it.id).also { newId -> require(newId > 0); createdId = newId }
                }
                checkSession(selected.session)
                val response = api.add(PlaylistTracksManipulateRequest("add", target, "[\"${selected.song.id}\"]"), selected.session)
                require(response.code == 200 || response.code == 502) { response.message ?: "添加失败（${response.code}），请重试" }
                SongCollectDelegate.clearCache()
                _selection.value = null
                _messages.emit(if (response.code == 502) "歌曲已在此歌单中" else "已添加到歌单")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                // A created playlist remains selectable if adding the song failed; retry never creates another.
                val newItem = createdId?.let { UserPlaylist(id = it, name = name.orEmpty(), userId = selected.session.uid) }
                _selection.value = selected.copy(saving = false, items = selected.items + listOfNotNull(newItem),
                    error = (if (newItem != null) "歌单已创建，请点击该歌单重试添加。" else "") + error.userMessage())
            }
        }
    }

    fun dismissPlaylists() {
        if (_selection.value?.saving == true) return
        selectionJob?.cancel()
        _selection.value = null
    }

    fun download(song: Track) {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            val session = preferences.currentSessionTag() ?: run { _messages.emit("请先登录后下载歌曲"); return@launch }
            _download.value = SongDownloadState(song)
            try {
                val source = api.download(SongDownloadRequest(song.id), session).requireFullDownload(song.id)
                checkSession(session)
                downloads.download(song, source) { percent -> _download.update { it?.copy(progress = percent) } }
                _download.value = SongDownloadState(song, progress = 100, done = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                _download.value = SongDownloadState(song, error = error.userMessage())
            }
        }
    }

    fun dismissDownload() { downloadJob?.cancel(); _download.value = null }

    private suspend fun checkSession(session: UserSessionTag) {
        if (preferences.currentSessionTag() != session) throw SessionChangedException()
    }
}

private fun Exception.userMessage(): String = when (this) {
    is SessionChangedException -> message!!
    is java.net.UnknownHostException, is java.net.SocketTimeoutException -> "网络连接失败，请重试"
    else -> message?.takeIf { it.isNotBlank() } ?: "操作失败，请重试"
}
