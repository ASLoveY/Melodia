package com.lin0721.linmusic.feature.settings.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lin0721.linmusic.core.player.ldac.LdacState
import com.lin0721.linmusic.core.player.ldac.PcmFormat
import androidx.media3.common.C

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun PcmFormat.describe(): String {
    val encodingName = when (encoding) {
        C.ENCODING_PCM_FLOAT -> "32-bit float"
        C.ENCODING_PCM_24BIT -> "24-bit PCM"
        C.ENCODING_PCM_32BIT -> "32-bit PCM"
        C.ENCODING_PCM_16BIT -> "16-bit PCM"
        else -> "精度未知"
    }
    return "${sampleRate / 1000.0} kHz / $encodingName"
}

@Composable
fun LdacSettingsContent(viewModel: SettingsViewModel) {
    val enabled by viewModel.ldacEnabled.collectAsStateWithLifecycle()
    val state by viewModel.ldacMonitor.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(viewModel.ldacMonitor.hasPermission()) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { hasPermission = viewModel.ldacMonitor.hasPermission() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LdacSettingsPanel(enabled, state, viewModel::updateLdacEnabled, {
        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }, if (Build.VERSION.SDK_INT >= 31 && !hasPermission) ({ permission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) else null)
}

@Composable
internal fun LdacSettingsPanel(enabled: Boolean, state: LdacState, onToggle: (Boolean) -> Unit, onBluetoothSettings: () -> Unit, onPermission: (() -> Unit)?) {
    SettingsGroupCard("蓝牙 Hi-Res 输出") {
        SettingsSwitchRow("Hi-Res 输出", "蓝牙 A2DP 播放时使用系统原生引擎，尝试高采样率直出；暂时停用交叉淡化和响度均衡", enabled, onToggle)
        Text(when {
            !enabled -> "Hi-Res 输出已关闭"
            state.fallback -> "高精度输出失败，已回退普通播放"
            state.precisionActive -> "原生高精度播放已启用"
            else -> "等待蓝牙 A2DP 播放路由"
        }, modifier = Modifier.testTag("ldac_status"))
        Text("当前路由：${state.routeName}")
        if (state.nativePlayback) {
            Text("音源：${state.sourceSampleRate?.let { "${it / 1000.0} kHz" } ?: "采样率未提供"} · ${state.sourceMimeType ?: "格式未提供"}")
            Text("原生音频会话：${state.nativeSessionId ?: "等待准备"}")
            Text("实际混音/直出格式需设备验证；音源采样率和 LDAC 配置不能代替系统输出测量。", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("解码 PCM：${state.decoded?.describe() ?: "等待播放"}")
            Text("AudioTrack：${state.output?.describe() ?: "等待播放"}")
        }
        Text(if (state.confirmedLdac) "系统报告：LDAC ${state.codec?.sampleRate?.let { "${it / 1000.0} kHz" }.orEmpty()} ${state.codec?.bits?.let { "$it-bit" }.orEmpty()}"
            else "蓝牙编码：${state.matchingCodec?.name ?: "未确认；请在系统设置中检查 LDAC"}")
        Text("LDAC 由手机与耳机协商，本应用不能强制切换或锁定 990 kbps。显示的 PCM 格式不代表蓝牙链路格式，也不代表无损或 bit-perfect。", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = onBluetoothSettings) { Text("蓝牙设置") }
            if (onPermission != null) TextButton(onClick = onPermission) { Text("授权附近设备") }
        }
        Text("先在系统设备详情中开启 LDAC/高清音频；如果编码状态未确认，可重新连接耳机后播放。", style = MaterialTheme.typography.bodySmall)
    }
}
