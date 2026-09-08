package com.lin0721.linmusic.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin0721.linmusic.LocalBottomOverlayInset
import com.lin0721.linmusic.core.ui.theme.MelodiaSpacing

@Composable
fun PlaybackDownloadSettingsView(viewModel: SettingsViewModel) {
    val autoPlayNext by viewModel.autoPlayNext.collectAsStateWithLifecycle()
    val streamCacheEnabled by viewModel.streamCacheEnabled.collectAsStateWithLifecycle()
    val effects by viewModel.playbackEffects.collectAsStateWithLifecycle()

    // 渲染播放与下载的子设置项
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(MelodiaSpacing.md),
        contentPadding = PaddingValues(top = 8.dp, bottom = LocalBottomOverlayInset.current + 16.dp)
    ) {
        item { PlaybackEffectsSettingsContent(effects, viewModel::updatePlaybackEffects) }
        item {
            SettingsGroupCard("播放参数") {
                SettingsSwitchRow(
                    title = "自动播放推荐新歌",
                    subtitle = "当前曲目播放完毕后自动接入相似推荐",
                    checked = autoPlayNext,
                    onCheckedChange = { viewModel.updateAutoPlayNext(it) }
                )
            }
        }
        item {
            SettingsGroupCard("播放缓存") {
                SettingsSwitchRow(
                    title = "边听边存",
                    subtitle = "在线播放歌曲时自动缓存到本地",
                    checked = streamCacheEnabled,
                    onCheckedChange = { viewModel.updateStreamCacheEnabled(it) }
                )
            }
        }
    }
}
