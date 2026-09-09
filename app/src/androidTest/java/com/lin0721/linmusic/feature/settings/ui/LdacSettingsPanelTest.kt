package com.lin0721.linmusic.feature.settings.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.lin0721.linmusic.core.player.ldac.*
import com.lin0721.linmusic.core.ui.theme.MelodiaTheme
import org.junit.Rule
import org.junit.Test

class LdacSettingsPanelTest {
    @get:Rule val compose = createComposeRule()
    @Test fun nativeSourceFormatDoesNotMasqueradeAsConfirmedOutput() {
        var state by mutableStateOf(LdacState(precisionActive = true, nativePlayback = true,
            sourceSampleRate = 96000, sourceMimeType = "audio/flac", nativeSessionId = 123))
        compose.setContent { MelodiaTheme { LdacSettingsPanel(true, state, {}, {}, null) } }
        compose.onNodeWithText("原生高精度播放已启用").assertExists()
        compose.onNodeWithText("音源：96.0 kHz · audio/flac").assertExists()
        compose.onNodeWithText("实际混音/直出格式需设备验证；音源采样率和 LDAC 配置不能代替系统输出测量。").assertExists()
        compose.onNodeWithText("AudioTrack：96.0 kHz / 32-bit float").assertDoesNotExist()
        compose.onNodeWithText("蓝牙编码：未确认；请在系统设置中检查 LDAC").assertExists()
        compose.runOnIdle { state = state.copy(bluetoothRoute = true, routeAddress = "AA", codec = BluetoothCodecObservation("AA", "LDAC", 96000, 24)) }
        compose.onNodeWithText("系统报告：LDAC 96.0 kHz 24-bit").assertExists()
        compose.runOnIdle { state = state.copy(routeAddress = "BB") }
        compose.onNodeWithText("蓝牙编码：未确认；请在系统设置中检查 LDAC").assertExists()
    }
}
