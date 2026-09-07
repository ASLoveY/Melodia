package com.lin0721.linmusic.feature.account.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.R
import com.lin0721.linmusic.core.ui.components.MelodiaButton
import com.lin0721.linmusic.core.ui.components.MelodiaTextButton
import com.lin0721.linmusic.core.ui.components.SecondaryScreenScaffold
import org.koin.androidx.compose.koinViewModel

@Composable
fun AccountScreen(onBack: () -> Unit) {
    val viewModel: AccountViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.refresh() }
    DisposableEffect(viewModel) { onDispose { viewModel.leave() } }

    val requestBack: () -> Unit = {
        val ready = state as? ProfileEditorState.Ready
        if (ready?.isSaving != true) {
            if (ready?.hasChanges == true) confirmDiscard = true else onBack()
        }
    }
    val ready = state as? ProfileEditorState.Ready
    BackHandler(enabled = ready?.hasChanges == true || ready?.isSaving == true, onBack = requestBack)

    SecondaryScreenScaffold(title = stringResource(R.string.profile_title), onBack = requestBack) {
        when (val current = state) {
            ProfileEditorState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            ProfileEditorState.SignedOut -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.profile_login_required))
            }
            is ProfileEditorState.Error -> Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(current.message)
                MelodiaTextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.action_retry)) }
            }
            is ProfileEditorState.Ready -> ProfileEditorContent(
                state = current,
                onNicknameChange = viewModel::editNickname,
                onSignatureChange = viewModel::editSignature,
                onSave = viewModel::save,
                onRefresh = viewModel::refresh
            )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.profile_discard_title)) },
            text = { Text(stringResource(R.string.profile_discard_message)) },
            confirmButton = {
                MelodiaTextButton(onClick = { confirmDiscard = false; onBack() }) {
                    Text(stringResource(R.string.profile_discard))
                }
            },
            dismissButton = {
                MelodiaTextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.profile_continue_editing))
                }
            }
        )
    }
}

@Composable
fun ProfileEditorContent(
    state: ProfileEditorState.Ready,
    onNicknameChange: (String) -> Unit,
    onSignatureChange: (String) -> Unit,
    onSave: () -> Unit,
    onRefresh: () -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 20.dp, bottom = LocalBottomOverlayInset.current + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(
                    model = state.profile.avatarUrl,
                    contentDescription = stringResource(R.string.profile_avatar),
                    modifier = Modifier.size(88.dp).clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.profile_user_id, state.profile.userId), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            OutlinedTextField(
                value = state.nickname,
                onValueChange = onNicknameChange,
                label = { Text(stringResource(R.string.profile_nickname)) },
                enabled = !state.isSaving && state.profile.isEditable,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("profile_nickname")
            )
        }
        item {
            OutlinedTextField(
                value = state.signature,
                onValueChange = onSignatureChange,
                label = { Text(stringResource(R.string.profile_signature)) },
                enabled = !state.isSaving && state.profile.isEditable,
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth().testTag("profile_signature")
            )
        }
        if (!state.profile.isEditable) {
            item {
                Text(stringResource(R.string.profile_incomplete), color = MaterialTheme.colorScheme.error)
                MelodiaTextButton(onClick = onRefresh, enabled = !state.isSaving) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
        state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (state.saved) item { Text(stringResource(R.string.profile_saved)) }
        item {
            MelodiaButton(
                onClick = onSave,
                enabled = state.profile.isEditable && state.hasChanges && state.nickname.isNotBlank() && !state.isSaving,
                modifier = Modifier.fillMaxWidth().testTag("profile_save")
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(if (state.isSaving) R.string.profile_saving else R.string.profile_save))
            }
        }
    }
}
