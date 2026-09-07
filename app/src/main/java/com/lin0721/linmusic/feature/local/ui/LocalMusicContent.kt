package com.lin0721.linmusic.feature.local.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SelectAll
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
import com.lin0721.linmusic.feature.local.domain.LocalImportProgress
import com.lin0721.linmusic.feature.local.domain.LocalMusicDirectory
import com.lin0721.linmusic.feature.local.domain.LocalTrack
import org.koin.androidx.compose.koinViewModel

@Composable
fun LocalMusicContent(modifier: Modifier = Modifier) {
    val viewModel: LocalMusicViewModel = koinViewModel()
    val tracks by viewModel.visibleTracks.collectAsStateWithLifecycle()
    val allTracks by viewModel.tracks.collectAsStateWithLifecycle()
    val directories by viewModel.directories.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val importing by viewModel.isImporting.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val removing by viewModel.isRemoving.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val current by viewModel.currentTrack.collectAsStateWithLifecycle()
    val playing by viewModel.isPlaying.collectAsStateWithLifecycle()

    var pendingRemoval by remember { mutableStateOf<LocalTrack?>(null) }
    var pendingBatchRemoval by remember { mutableStateOf<Set<String>?>(null) }
    var pendingDirectoryRemoval by remember { mutableStateOf<LocalMusicDirectory?>(null) }
    var showDirectories by remember { mutableStateOf(false) }
    val busy = importing || removing

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
        viewModel::importUris
    )
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::importDirectory)
    }

    BackHandler(enabled = selection.active || showDirectories) {
        when {
            selection.active -> viewModel.exitSelection()
            else -> showDirectories = false
        }
    }

    // Filter changes invalidate the current selection; the ViewModel also clears it so stale ids
    // cannot be submitted after a search or sort change.
    LaunchedEffect(query, sort) {
        if (selection.active) viewModel.exitSelection()
    }
    LaunchedEffect(viewModel) { viewModel.messages.collect { ToastManager.showToast(it) } }

    LocalMusicList(
        tracks = tracks,
        totalCount = allTracks.size,
        query = query,
        sort = sort,
        importing = importing,
        importProgress = importProgress,
        error = error,
        playingId = current?.mediaId,
        isPlaying = playing,
        selectionActive = selection.active,
        selectedIds = selection.selectedIds,
        busy = busy,
        directoryCount = directories.size,
        onQuery = viewModel::setQuery,
        onSort = viewModel::setSort,
        onImport = { picker.launch(arrayOf("audio/*")) },
        onImportDirectory = { directoryPicker.launch(null) },
        onOpenDirectories = { showDirectories = true },
        onCancelImport = viewModel::cancelImport,
        onPlay = viewModel::play,
        onRemove = { if (!busy && !selection.active) pendingRemoval = it },
        onToggleSelection = viewModel::toggleSelection,
        onStartDragSelection = viewModel::beginDragSelection,
        onDragOverSelection = viewModel::updateDragSelection,
        onEndDragSelection = viewModel::endDragSelection,
        onToggleSelectAll = viewModel::toggleSelectAll,
        onExitSelection = viewModel::exitSelection,
        onRemoveSelected = { ids ->
            if (!busy && ids.isNotEmpty()) pendingBatchRemoval = ids.toSet()
        },
        modifier = modifier
    )

    pendingRemoval?.let { track ->
        LocalTrackRemovalDialog(
            track = track,
            removing = removing,
            onDismiss = { pendingRemoval = null },
            onConfirm = { viewModel.remove(track) { pendingRemoval = null } }
        )
    }

    pendingBatchRemoval?.let { ids ->
        AlertDialog(
            onDismissRequest = { if (!removing) pendingBatchRemoval = null },
            title = { Text(stringResource(R.string.local_remove_selected)) },
            text = { Text(stringResource(R.string.local_remove_selected_confirmation, ids.size)) },
            confirmButton = {
                MelodiaTextButton(
                    onClick = {
                        viewModel.removeTracks(ids) {
                            pendingBatchRemoval = null
                            viewModel.exitSelection()
                        }
                    },
                    enabled = !removing
                ) { Text(stringResource(R.string.local_remove_selected)) }
            },
            dismissButton = {
                MelodiaTextButton(
                    onClick = { pendingBatchRemoval = null },
                    enabled = !removing
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showDirectories) {
        LocalDirectoriesDialog(
            directories = directories,
            tracks = allTracks,
            busy = busy,
            onDismiss = { showDirectories = false },
            onRemove = { pendingDirectoryRemoval = it }
        )
    }

    pendingDirectoryRemoval?.let { directory ->
        val count = allTracks.count { directory.id in it.sourceDirectoryIds }
        AlertDialog(
            onDismissRequest = { if (!removing) pendingDirectoryRemoval = null },
            title = { Text(stringResource(R.string.local_remove_directory)) },
            text = {
                Text(stringResource(R.string.local_remove_directory_confirmation, directory.name, count))
            },
            confirmButton = {
                MelodiaTextButton(
                    onClick = {
                        viewModel.removeDirectory(directory.id) { pendingDirectoryRemoval = null }
                    },
                    enabled = !busy
                ) { Text(stringResource(R.string.local_remove_directory)) }
            },
            dismissButton = {
                MelodiaTextButton(
                    onClick = { pendingDirectoryRemoval = null },
                    enabled = !busy
                ) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
}

@Composable
private fun LocalTrackRemovalDialog(
    track: LocalTrack,
    removing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!removing) onDismiss() },
        title = { Text(stringResource(R.string.local_remove)) },
        text = { Text(stringResource(R.string.local_remove_confirmation, track.title)) },
        confirmButton = {
            MelodiaTextButton(onClick = onConfirm, enabled = !removing) {
                Text(stringResource(R.string.local_remove))
            }
        },
        dismissButton = {
            MelodiaTextButton(onClick = onDismiss, enabled = !removing) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun LocalDirectoriesDialog(
    directories: List<LocalMusicDirectory>,
    tracks: List<LocalTrack>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRemove: (LocalMusicDirectory) -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.local_directories)) },
        text = {
            if (directories.isEmpty()) {
                Text(stringResource(R.string.local_directories_empty))
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(directories, key = { it.id }) { directory ->
                        val count = tracks.count { directory.id in it.sourceDirectoryIds }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(directory.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    stringResource(R.string.local_directory_tracks, count),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(
                                onClick = { onRemove(directory) },
                                enabled = !busy,
                                modifier = Modifier.testTag("local_directory_remove_${directory.id}")
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.local_remove_directory)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            MelodiaTextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
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
    modifier: Modifier = Modifier,
    importProgress: LocalImportProgress? = null,
    onImportDirectory: () -> Unit = {},
    onCancelImport: () -> Unit = {},
    selectionActive: Boolean = false,
    selectedIds: Set<String> = emptySet(),
    busy: Boolean = importing,
    directoryCount: Int = 0,
    onOpenDirectories: () -> Unit = {},
    onToggleSelection: (String) -> Unit = {},
    onStartDragSelection: (String) -> Unit = {},
    onDragOverSelection: (String) -> Unit = {},
    onEndDragSelection: () -> Unit = {},
    onToggleSelectAll: () -> Unit = {},
    onExitSelection: () -> Unit = {},
    onRemoveSelected: (Set<String>) -> Unit = {}
) {
    var showSort by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val allVisibleSelected = tracks.isNotEmpty() && tracks.all { it.id in selectedIds }

    Column(modifier.fillMaxSize()) {
        // Keep the three header slots at fixed heights. Entering selection mode must not move the
        // LazyColumn under the user's finger while the long-press drag is already in progress.
        Box(Modifier.fillMaxWidth().height(56.dp)) {
            if (selectionActive) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.local_selection_count, selectedIds.size),
                        modifier = Modifier.weight(1f)
                    )
                    MelodiaTextButton(
                        onClick = onToggleSelectAll,
                        enabled = !busy && tracks.isNotEmpty(),
                        modifier = Modifier.testTag("local_select_all")
                    ) {
                        Icon(Icons.Default.SelectAll, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(if (allVisibleSelected) R.string.local_clear_selection else R.string.local_select_all))
                    }
                    MelodiaIconButton(
                        onClick = onExitSelection,
                        enabled = !busy,
                        modifier = Modifier.testTag("local_exit_selection")
                    ) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.local_exit_selection)) }
                }
            } else {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.local_track_count, totalCount), modifier = Modifier.weight(1f))
                    MelodiaTextButton(
                        onClick = onOpenDirectories,
                        enabled = !busy,
                        modifier = Modifier.testTag("local_directories")
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.local_directories_count, directoryCount))
                    }
                    Box {
                        MelodiaTextButton(onClick = { showSort = true }, enabled = !busy) {
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
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(56.dp),
            contentAlignment = Alignment.CenterEnd
        ) {
            if (selectionActive) {
                MelodiaButton(
                    onClick = { onRemoveSelected(selectedIds.toSet()) },
                    enabled = !busy && selectedIds.isNotEmpty(),
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("local_remove_selected")
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.local_remove_selected))
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MelodiaButton(
                        onClick = onImport,
                        enabled = !importing && !busy,
                        modifier = Modifier.weight(1f).testTag("local_import")
                    ) {
                        Text(stringResource(if (importing) R.string.local_importing else R.string.local_import))
                    }
                    MelodiaButton(
                        onClick = onImportDirectory,
                        enabled = !importing && !busy,
                        modifier = Modifier.weight(1f).testTag("local_import_directory")
                    ) { Text(stringResource(R.string.local_import_directory)) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(32.dp)) {
            if (!selectionActive) {
                Text(
                    stringResource(R.string.local_import_directory_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text(stringResource(R.string.local_search)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("local_search")
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (importing) {
            val progress = importProgress
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            progress?.isSaving == true -> stringResource(R.string.local_import_saving)
                            progress != null -> stringResource(
                                R.string.local_import_directory_progress,
                                progress.scanned,
                                progress.imported,
                                progress.skippedShort
                            )
                            else -> stringResource(R.string.local_importing)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    if (progress != null) {
                        MelodiaTextButton(
                            onClick = onCancelImport,
                            enabled = !progress.isSaving,
                            modifier = Modifier.testTag("local_import_cancel")
                        ) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
        }
        if (tracks.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(if (query.isBlank()) R.string.local_empty else R.string.local_no_results))
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .localDragSelection(
                        listState = listState,
                        enabled = !busy,
                        onStart = onStartDragSelection,
                        onDragOver = onDragOverSelection,
                        onEnd = onEndDragSelection
                    ),
                contentPadding = PaddingValues(bottom = LocalBottomOverlayInset.current + 16.dp)
            ) {
                items(tracks, key = { it.id }) { track ->
                    val selected = playingId == "local:${track.uri}"
                    val isSelected = track.id in selectedIds
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) {
                                if (selectionActive) onToggleSelection(track.id) else onPlay(track)
                            }
                            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                            .testTag("local_row_${track.id}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectionActive) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onToggleSelection(track.id) },
                                enabled = !busy,
                                modifier = Modifier.size(48.dp).testTag("local_select_${track.id}")
                            )
                        } else {
                            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                    if (selected) { if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle } else Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(42.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${track.artist} · ${track.album}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (track.durationMs > 0) {
                            Text(
                                "${track.durationMs / 60000}:${(track.durationMs / 1000 % 60).toString().padStart(2, '0')}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (!selectionActive) {
                            MelodiaIconButton(onClick = { onRemove(track) }, enabled = !busy) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.library_more_actions, track.title))
                            }
                        }
                    }
                }
            }
        }
    }
}
