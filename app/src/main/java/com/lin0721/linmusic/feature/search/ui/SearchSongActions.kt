package com.lin0721.linmusic.feature.search.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.ui.components.CreatePlaylistDialog
import com.lin0721.linmusic.core.ui.components.SongRow
import com.lin0721.linmusic.core.ui.components.SongRowData
import com.lin0721.linmusic.core.ui.components.ToastManager
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSongOptionsSheet(song: Track, onDismiss: () -> Unit, onQueue: (Boolean) -> Unit,
    onCollect: () -> Unit, onDownload: () -> Unit, onArtist: (Long) -> Unit, onAlbum: (Long) -> Unit,
    onShare: () -> Unit
) {
    var choosingArtist by remember(song.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            SongRow(SongRowData(song.id, song.name, song.ar.joinToString(" / ") { it.name }, song.al.picUrl), onClick = {})
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            if (choosingArtist) {
                ActionRow("返回歌曲选项", Icons.AutoMirrored.Rounded.ArrowBack) { choosingArtist = false }
                song.ar.filter { it.id > 0 }.distinctBy { it.id }.forEach { artist ->
                    ActionRow(artist.name, Icons.Rounded.Person) { onArtist(artist.id) }
                }
            } else {
                ActionRow("下一首播放", Icons.Rounded.SkipNext) { onQueue(true) }
                ActionRow("加入播放队列末尾", Icons.AutoMirrored.Rounded.QueueMusic) { onQueue(false) }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                ActionRow("添加到歌单", Icons.AutoMirrored.Rounded.PlaylistAdd, onClick = onCollect)
                ActionRow("下载到本地", Icons.Rounded.Download, onClick = onDownload)
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                val artists = song.ar.filter { it.id > 0 }.distinctBy { it.id }
                ActionRow("查看歌手", Icons.Rounded.Person, enabled = artists.isNotEmpty()) {
                    if (artists.size == 1) onArtist(artists.single().id) else choosingArtist = true
                }
                ActionRow("查看专辑", Icons.Rounded.Album, enabled = song.al.id > 0) { onAlbum(song.al.id) }
                ActionRow("分享歌曲", Icons.Rounded.Share, onClick = onShare)
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(icon, null) },
        modifier = Modifier.fillMaxWidth().testTag("song_action_$label").clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(headlineColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSongActions(song: Track?, onDismiss: () -> Unit, onArtist: (Long) -> Unit, onAlbum: (Long) -> Unit,
    viewModel: SearchSongActionsViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.messages.collect { ToastManager.showToast(it) } }
    if (song != null) SearchSongOptionsSheet(song, onDismiss,
        onQueue = { viewModel.enqueue(song, it); onDismiss() },
        onCollect = { onDismiss(); viewModel.choosePlaylist(song) },
        onDownload = { onDismiss(); viewModel.download(song) },
        onArtist = { onDismiss(); onArtist(it) }, onAlbum = { onDismiss(); onAlbum(it) },
        onShare = {
            onDismiss()
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "${song.name} — ${song.ar.joinToString(" / ") { it.name }}\nhttps://music.163.com/song?id=${song.id}")
            }, "分享歌曲"))
        })

    selection?.let { state ->
        var create by remember(state.song.id) { mutableStateOf(false) }
        val saving by rememberUpdatedState(state.saving)
        ModalBottomSheet(onDismissRequest = viewModel::dismissPlaylists, sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true, confirmValueChange = { !saving || it != SheetValue.Hidden })) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text("添加到歌单", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(state.song.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.loading || state.saving) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 16.dp))
                    Text(if (state.saving) "正在添加，请稍候…" else "正在读取歌单…")
                } else {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.error != null) TextButton(onClick = viewModel::retryPlaylists) { Text("重新读取歌单") }
                    TextButton(onClick = { create = true }) { Text("新建歌单") }
                    if (state.items.isEmpty() && state.error == null) Text("暂无自建歌单，可以先新建一个")
                    state.items.forEach { playlist ->
                        ListItem(headlineContent = { Text(playlist.name) }, supportingContent = { Text("${playlist.trackCount} 首歌曲") },
                            modifier = Modifier.clickable { viewModel.addToPlaylist(playlist.id) })
                    }
                }
            }
        }
        if (create) CreatePlaylistDialog(onDismiss = { create = false }, onCreate = { create = false; viewModel.addToPlaylist(name = it) })
    }
    download?.let { state ->
        AlertDialog(onDismissRequest = viewModel::dismissDownload, title = { Text(if (state.done) "下载完成" else "下载到本地") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.song.name)
                when {
                    state.done -> Text("已加入音乐库 → 本地音乐，可以离线播放和管理。")
                    state.error != null -> Text(state.error, color = MaterialTheme.colorScheme.error)
                    else -> {
                        if (state.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(state.progress?.let { "已下载 $it%" } ?: "正在获取下载链接…")
                    }
                }
            } },
            confirmButton = { TextButton(onClick = if (state.error != null) ({ viewModel.download(state.song) }) else viewModel::dismissDownload) {
                Text(if (state.done) "完成" else if (state.error != null) "重试" else "取消下载")
            } },
            dismissButton = { if (state.error != null) TextButton(onClick = viewModel::dismissDownload) { Text("关闭") } })
    }
}
