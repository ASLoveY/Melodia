package com.lin0721.linmusic.feature.library.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lin0721.linmusic.R
import com.lin0721.linmusic.core.ui.components.MelodiaDragHandle
import com.lin0721.linmusic.core.ui.components.MelodiaTextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryItemActionsSheet(
    item: LibraryItem,
    onDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onRemovePlaylist: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, dragHandle = { MelodiaDragHandle() }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(if (item.isPinned) R.string.library_unpin else R.string.library_pin),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onTogglePin).padding(vertical = 18.dp)
            )
            if (item.type == LibraryItemType.PLAYLIST && !item.isLikedSongs &&
                (item.id.toLongOrNull() ?: 0) > 0 && (item.ownerId ?: 0) > 0
            ) {
                Text(
                    stringResource(if (item.isOwnedByMe) R.string.playlist_delete else R.string.playlist_unsubscribe),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onRemovePlaylist).padding(vertical = 18.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun LibraryPlaylistRemovalDialog(
    state: PlaylistRemovalState.Confirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val action = if (state.kind == PlaylistRemovalKind.DELETE) R.string.playlist_delete else R.string.playlist_unsubscribe
    val message = if (state.kind == PlaylistRemovalKind.DELETE) {
        R.string.playlist_delete_confirmation
    } else R.string.playlist_unsubscribe_confirmation
    AlertDialog(
        onDismissRequest = { if (!state.isSubmitting) onDismiss() },
        title = { Text(stringResource(action)) },
        text = {
            Column {
                Text(stringResource(message, state.target.title))
                state.error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            MelodiaTextButton(onClick = onConfirm, enabled = !state.isSubmitting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.isSubmitting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(if (state.error == null) action else R.string.action_retry), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            MelodiaTextButton(onClick = onDismiss, enabled = !state.isSubmitting) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
