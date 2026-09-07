package com.lin0721.linmusic.feature.local.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.R
import com.lin0721.linmusic.core.ui.components.MelodiaButton
import com.lin0721.linmusic.core.ui.components.MelodiaIconButton
import com.lin0721.linmusic.core.ui.components.MelodiaTextButton
import com.lin0721.linmusic.core.ui.components.ToastManager
import com.lin0721.linmusic.feature.local.domain.LocalTrack
import org.koin.androidx.compose.koinViewModel

@Composable
fun LocalMusicContent(modifier: Modifier = Modifier) {
    val viewModel: LocalMusicViewModel = koinViewModel()
    val tracks by viewModel.visibleTracks.collectAsStateWithLifecycle()
    val allTracks by viewModel.tracks.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val importing by viewModel.isImporting.collectAsStateWithLifecycle()
    val removing by viewModel.isRemoving.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val current by viewModel.currentTrack.collectAsStateWithLifecycle()
    val playing by viewModel.isPlaying.collectAsStateWithLifecycle()
    var pendingRemoval by remember { mutableStateOf<LocalTrack?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), viewModel::importUris)
    LaunchedEffect(viewModel) { viewModel.messages.collect { ToastManager.showToast(it) } }

    LocalMusicList(
        tracks = tracks,
        totalCount = allTracks.size,
        query = query,
        sort = sort,
        importing = importing,
        error = error,
        playingId = current?.mediaId,
        isPlaying = playing,
        onQuery = viewModel::setQuery,
        onSort = viewModel::setSort,
        onImport = { picker.launch(arrayOf("audio/*")) },
        onPlay = viewModel::play,
        onRemove = { if (!importing) pendingRemoval = it },
        modifier = modifier
    )
    pendingRemoval?.let { track ->
        AlertDialog(
            onDismissRequest = { if (!removing) pendingRemoval = null },
            title = { Text(stringResource(R.string.local_remove)) },
            text = { Text(stringResource(R.string.local_remove_confirmation, track.title)) },
            confirmButton = {
                MelodiaTextButton(onClick = { viewModel.remove(track) { pendingRemoval = null } }, enabled = !removing) {
                    Text(stringResource(R.string.local_remove))
                }
            },
            dismissButton = {
                MelodiaTextButton(onClick = { pendingRemoval = null }, enabled = !removing) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
fun LocalMusicList(
    tracks: List<LocalTrack>,
    totalCount: Int,
    query: String,
    sort: LocalMusicSort,
    importing: Boolean,
    error: String?,
    playingId: String?,
    isPlaying: Boolean,
    onQuery: (String) -> Unit,
    onSort: (LocalMusicSort) -> Unit,
    onImport: () -> Unit,
    onPlay: (LocalTrack) -> Unit,
    onRemove: (LocalTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSort by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.local_track_count, totalCount), modifier = Modifier.weight(1f))
            Box {
                MelodiaTextButton(onClick = { showSort = true }) {
                    Text(stringResource(when (sort) {
                        LocalMusicSort.RECENT -> R.string.local_sort_recent
                        LocalMusicSort.TITLE -> R.string.local_sort_title
                        LocalMusicSort.ARTIST -> R.string.local_sort_artist
                    }))
                }
                DropdownMenu(expanded = showSort, onDismissRequest = { showSort = false }) {
                    LocalMusicSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(when (option) {
                                LocalMusicSort.RECENT -> R.string.local_sort_recent
                                LocalMusicSort.TITLE -> R.string.local_sort_title
                                LocalMusicSort.ARTIST -> R.string.local_sort_artist
                            })) },
                            onClick = { onSort(option); showSort = false }
                        )
                    }
                }
            }
            MelodiaButton(onClick = onImport, enabled = !importing, modifier = Modifier.testTag("local_import")) {
                Text(stringResource(if (importing) R.string.local_importing else R.string.local_import))
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text(stringResource(R.string.local_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("local_search")
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (tracks.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(if (query.isBlank()) R.string.local_empty else R.string.local_no_results))
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = LocalBottomOverlayInset.current + 16.dp)
            ) {
                items(tracks, key = { it.id }) { track ->
                    val selected = playingId == "local:${track.uri}"
                    Row(
                        Modifier.fillMaxWidth().clickable { onPlay(track) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (selected) { if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle } else Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${track.artist} · ${track.album}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (track.durationMs > 0) {
                            Text("${track.durationMs / 60000}:${(track.durationMs / 1000 % 60).toString().padStart(2, '0')}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        MelodiaIconButton(onClick = { onRemove(track) }, enabled = !importing) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.library_more_actions, track.title))
                        }
                    }
                }
            }
        }
    }
}
