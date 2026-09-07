package com.lin0721.linmusic.feature.local.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lin0721.linmusic.R
import com.lin0721.linmusic.core.network.ResourceProvider
import com.lin0721.linmusic.core.player.PlayerManager
import com.lin0721.linmusic.core.player.QueueItem
import com.lin0721.linmusic.feature.local.domain.LocalMusicRepository
import com.lin0721.linmusic.feature.local.domain.LocalTrack
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class LocalMusicSort { RECENT, TITLE, ARTIST }

fun filterLocalTracks(tracks: List<LocalTrack>, query: String, sort: LocalMusicSort): List<LocalTrack> {
    val keyword = query.trim()
    val filtered = tracks.filter { keyword.isBlank() ||
        it.title.contains(keyword, ignoreCase = true) ||
        it.artist.contains(keyword, ignoreCase = true) ||
        it.album.contains(keyword, ignoreCase = true)
    }
    return when (sort) {
        LocalMusicSort.RECENT -> filtered.sortedByDescending { it.addedAt }
        LocalMusicSort.TITLE -> filtered.sortedBy { it.title.lowercase(Locale.ROOT) }
        LocalMusicSort.ARTIST -> filtered.sortedWith(compareBy<LocalTrack> { it.artist.lowercase(Locale.ROOT) }.thenBy { it.title })
    }
}

class LocalMusicViewModel(
    private val repository: LocalMusicRepository,
    private val playerManager: PlayerManager,
    private val resources: ResourceProvider
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()
    private val _sort = MutableStateFlow(LocalMusicSort.RECENT)
    val sort = _sort.asStateFlow()
    private val _isImporting = MutableStateFlow(false)
    val isImporting = _isImporting.asStateFlow()
    private val _isRemoving = MutableStateFlow(false)
    val isRemoving = _isRemoving.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _messages = MutableSharedFlow<String>()
    val messages = _messages.asSharedFlow()

    val tracks = repository.tracks.onEach { _error.value = null }.catch {
        _error.value = resources.getString(R.string.local_load_failed)
        emit(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val visibleTracks = combine(tracks, query, sort, ::filterLocalTracks)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val currentTrack = playerManager.currentTrack
    val isPlaying = playerManager.isPlaying

    fun setQuery(value: String) { _query.value = value }
    fun setSort(value: LocalMusicSort) { _sort.value = value }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty() || _isImporting.value) return
        _isImporting.value = true
        viewModelScope.launch {
            try {
                val result = repository.importUris(uris)
                _messages.emit(resources.getString(R.string.local_import_result, result.imported, result.duplicates, result.failed))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _messages.emit(resources.getString(R.string.local_import_failed))
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun play(track: LocalTrack) {
        if (playerManager.currentTrack.value?.mediaId == "local:${track.uri}") {
            playerManager.togglePlayPause()
            return
        }
        val playlist = visibleTracks.value
        val index = playlist.indexOfFirst { it.id == track.id }
        if (index < 0) return
        playerManager.playQueue(playlist.map {
            QueueItem(songId = 0, title = it.title, artist = it.artist, coverUrl = "", localUri = it.uri)
        }, index, "本地音乐")
    }

    fun remove(track: LocalTrack, onRemoved: () -> Unit) {
        if (_isRemoving.value || _isImporting.value) return
        _isRemoving.value = true
        viewModelScope.launch {
            try {
                repository.remove(track.id)
                onRemoved()
                _messages.emit(resources.getString(R.string.local_removed))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _messages.emit(resources.getString(R.string.local_remove_failed))
            } finally {
                _isRemoving.value = false
            }
        }
    }
}
