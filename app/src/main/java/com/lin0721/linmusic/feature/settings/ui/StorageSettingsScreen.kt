package com.lin0721.linmusic.feature.settings.ui

import com.lin0721.linmusic.core.ui.theme.AppText
import com.lin0721.linmusic.core.ui.theme.AppAccent

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin0721.linmusic.core.ui.components.MelodiaTextButton
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.R
import com.lin0721.linmusic.core.ui.theme.NeteaseRed
import com.lin0721.linmusic.core.ui.theme.AppSurface
import com.lin0721.linmusic.core.ui.theme.AppTextSecondary
import com.lin0721.linmusic.core.ui.theme.MelodiaSpacing

@Composable
fun StorageSettingsView(viewModel: SettingsViewModel, context: Context) {
    val isLoading by viewModel.isCacheOperationRunning.collectAsStateWithLifecycle()
    val currentMaxSize by viewModel.audioCacheMaxSize.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }

    val currentSizeStr = when (currentMaxSize) {
        200 * 1024 * 1024L -> "200 MB"
        500 * 1024 * 1024L -> "500 MB"
        1024 * 1024 * 1024L -> "1 GB"
        2 * 1024 * 1024 * 1024L -> "2 GB"
        else -> "${currentMaxSize / (1024 * 1024)} MB"
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(MelodiaSpacing.md),
            contentPadding = PaddingValues(top = 8.dp, bottom = LocalBottomOverlayInset.current + 16.dp)
        ) {
            item {
                SettingsGroupCard("存储管理") {
                    // 清理缓存行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isLoading) { viewModel.clearApplicationCache(context) }
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("清理应用缓存", color = AppText, fontSize = 15.sp)
                            Text(stringResource(R.string.cache_clear_description), color = AppTextSecondary, fontSize = 12.sp)
                        }
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = null,
                            tint = AppAccent
                        )
                    }

                    HorizontalDivider(color = AppText.copy(alpha = 0.05f))

                    // 缓存大小上限设置行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isLoading) { showDialog = true }
                            .padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("最大音频缓存上限", color = AppText, fontSize = 15.sp)
                            Text("当前上限: $currentSizeStr", color = AppTextSecondary, fontSize = 12.sp)
                        }
                        Text(
                            text = "修改",
                            color = AppAccent,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // 修改容量 Dialog 弹窗
        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("最大音频缓存上限", color = AppText) },
                text = {
                    Column {
                        val options = listOf(
                            200 * 1024 * 1024L to "200 MB",
                            500 * 1024 * 1024L to "500 MB",
                            1024 * 1024 * 1024L to "1 GB",
                            2 * 1024 * 1024 * 1024L to "2 GB"
                        )
                        options.forEach { (size, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.updateAudioCacheMaxSize(context, size)
                                        showDialog = false
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = currentMaxSize == size,
                                    onClick = {
                                        viewModel.updateAudioCacheMaxSize(context, size)
                                        showDialog = false
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = AppAccent,
                                        unselectedColor = AppTextSecondary
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = label, color = AppText, fontSize = 16.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    MelodiaTextButton(onClick = { showDialog = false }) {
                        Text("取消", color = AppAccent)
                    }
                },
                containerColor = AppSurface
            )
        }

        // 加载指示器
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AppAccent)
            }
        }
    }
}
