package com.lin0721.linmusic.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.io.File

@Composable
fun BackgroundSettingsContent(viewModel: SettingsViewModel) {
    val settings by viewModel.background.collectAsStateWithLifecycle()
    val busy by viewModel.backgroundBusy.collectAsStateWithLifecycle()
    var opacity by remember(settings.transparency) { mutableFloatStateOf(settings.transparency.toFloat()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::importBackground) }
    SettingsGroupCard("应用背景图片") {
        settings.imagePath?.let {
            AsyncImage(File(it), "当前背景图片", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(140.dp))
        }
        Text("用于常规页面，播放器保持专辑封面背景", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(enabled = !busy, modifier = Modifier.testTag("choose_background"), onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Text(if (settings.imagePath == null) "选择本地图片" else "更换图片")
            }
            if (settings.imagePath != null) TextButton(enabled = !busy, modifier = Modifier.testTag("reset_background"), onClick = viewModel::resetBackground) { Text("恢复默认背景") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (settings.imagePath != null) {
            Text("图片透明度：${settings.transparency}%")
            Slider(value = opacity, onValueChange = { opacity = it; viewModel.previewBackgroundTransparency(it.toInt()) },
                onValueChangeFinished = { viewModel.saveBackgroundTransparency(opacity.toInt()) },
                valueRange = 0f..100f, enabled = !busy, modifier = Modifier.testTag("background_transparency"))
            Text("0% 最清晰，100% 隐藏；保留文字可读性衬底", style = MaterialTheme.typography.bodySmall)
        }
    }
}
