package com.lin0721.linmusic.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.lin0721.linmusic.core.preferences.PlaybackEffectsSettings
import kotlin.math.roundToInt

@Composable
fun PlaybackEffectsSettingsContent(settings: PlaybackEffectsSettings, onChange: (PlaybackEffectsSettings) -> Unit) {
    var seconds by remember(settings.crossfadeSeconds) { mutableFloatStateOf(settings.crossfadeSeconds.toFloat()) }
    SettingsGroupCard("歌曲过渡与响度") {
        Column {
            SettingsSwitchRow("交叉淡化", "仅在歌曲自然结束时重叠过渡，手动切歌立即响应", settings.crossfadeEnabled,
                { onChange(settings.copy(crossfadeEnabled = it)) })
            Text("过渡时长：${seconds.roundToInt()} 秒")
            Slider(seconds, { seconds = it }, enabled = settings.crossfadeEnabled, valueRange = 1f..12f, steps = 10,
                onValueChangeFinished = { onChange(settings.copy(crossfadeSeconds = seconds.roundToInt())) }, modifier = Modifier.testTag("crossfade_seconds"))
            SettingsSwitchRow("响度均衡", "平衡歌曲整体响度，保留强弱变化；首次播放逐步估算", settings.normalizationEnabled,
                { onChange(settings.copy(normalizationEnabled = it)) })
        }
    }
}
