package com.lin0721.linmusic.feature.create.ui

import com.lin0721.linmusic.core.ui.theme.AppText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin0721.linmusic.core.ui.components.MelodiaTextButton
import com.lin0721.linmusic.core.ui.components.MelodiaButton
import com.lin0721.linmusic.core.ui.theme.AppBackground
import com.lin0721.linmusic.core.ui.theme.BottomSheetShape
import com.lin0721.linmusic.core.ui.theme.DragHandleShape
import com.lin0721.linmusic.core.ui.theme.NeteaseRed
import com.lin0721.linmusic.core.ui.theme.PillRadius
import com.lin0721.linmusic.core.ui.theme.AppSurface
import com.lin0721.linmusic.core.ui.theme.AppSurfaceRaised
import com.lin0721.linmusic.core.ui.theme.AppTextSecondary
import org.koin.androidx.compose.koinViewModel
import com.lin0721.linmusic.core.ui.theme.MelodiaSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePopupMenu(
    onDismiss: () -> Unit,
    onLoginRequest: () -> Unit,
    openCreateDialogRequest: Long? = null,
    onCreateDialogRequestConsumed: (Long) -> Unit = {},
    onCreateDialogClosed: () -> Unit = {}
) {
    val viewModel: CreateViewModel = koinViewModel()
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val isCreating by viewModel.isCreating.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showCreateDialog by remember { mutableStateOf(false) }
    var handledCreateDialogRequest by remember { mutableStateOf<Long?>(null) }

    // 登录成功后由应用层传入一次性请求。等创建 ViewModel 的资料流就绪，
    // 再打开表单，避免表单先出现又被未登录状态覆盖。
    LaunchedEffect(openCreateDialogRequest, userProfile) {
        val request = openCreateDialogRequest
        if (request != null && userProfile != null && handledCreateDialogRequest != request) {
            handledCreateDialogRequest = request
            showCreateDialog = true
            onCreateDialogRequestConsumed(request)
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.toastEvent.collect { message ->
            com.lin0721.linmusic.core.ui.components.ToastManager.showToast(message)
        }
    }

    val closeCreateDialog = {
        if (showCreateDialog) {
            showCreateDialog = false
            onCreateDialogClosed()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PillRadius))
            .background(AppSurface)
            .padding(vertical = MelodiaSpacing.sm)
    ) {
        CreateMenuItem(
            icon = Icons.AutoMirrored.Rounded.QueueMusic,
            title = "歌单",
            subtitle = "创建包含歌曲或合集的歌单",
            onClick = {
                if (userProfile != null) {
                    showCreateDialog = true
                } else {
                    com.lin0721.linmusic.core.ui.components.ToastManager.showToast("请先登录以创建歌单")
                    onDismiss()
                    onLoginRequest()
                }
            }
        )

    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            isCreating = isCreating,
            onConfirm = { name, isPrivate ->
                viewModel.createNewPlaylist(name, isPrivate) {
                    closeCreateDialog()
                    onDismiss()
                }
            },
            onDismiss = closeCreateDialog
        )
    }
}

@Composable
private fun CreateMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(AppSurfaceRaised),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AppText,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = AppText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = AppTextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreatePlaylistDialog(
    isCreating: Boolean,
    onConfirm: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { if (!isCreating) onDismiss() },
        containerColor = AppSurface,
        shape = BottomSheetShape,
        dragHandle = {
            Box(modifier = Modifier.padding(top = 12.dp, bottom = MelodiaSpacing.xs)) {
                Surface(
                    modifier = Modifier.width(40.dp).height(4.dp),
                    shape = DragHandleShape,
                    color = AppText.copy(alpha = 0.3f)
                ) {}
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = MelodiaSpacing.lg, vertical = MelodiaSpacing.md)
        ) {
            Text(
                text = "新建歌单",
                color = AppText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = MelodiaSpacing.md)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppBackground)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (name.isEmpty()) {
                    Text("我的新歌单", color = AppTextSecondary, fontSize = 15.sp)
                }
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    textStyle = TextStyle(color = AppText, fontSize = 15.sp),
                    cursorBrush = SolidColor(NeteaseRed),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("设为隐私歌单", color = AppText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("仅自己可见", color = AppTextSecondary, fontSize = 12.sp)
                }
                Switch(
                    checked = isPrivate,
                    onCheckedChange = { isPrivate = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = NeteaseRed,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = AppSurfaceRaised
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MelodiaTextButton(
                    onClick = onDismiss,
                    enabled = !isCreating
                ) {
                    Text("取消", color = AppText)
                }
                Spacer(modifier = Modifier.width(16.dp))
                MelodiaButton(
                    onClick = { onConfirm(name, isPrivate) },
                    enabled = !isCreating && name.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = NeteaseRed),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = AppText,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("创建", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
