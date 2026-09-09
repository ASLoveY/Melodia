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
    @Test fun configuredPcmDoesNotMasqueradeAsConfirmedLdac() {
        var state by mutableStateOf(LdacState(precisionActive = true, output = PcmFormat(96000, 4)))
        compose.setContent { MelodiaTheme { LdacSettingsPanel(true, state, {}, {}, null) } }
        compose.onNodeWithText("应用高精度路径已启用").assertExists()
        compose.onNodeWithText("蓝牙编码：未确认；请在系统设置中检查 LDAC").assertExists()
        compose.runOnIdle { state = state.copy(bluetoothRoute = true, routeAddress = "AA", codec = BluetoothCodecObservation("AA", "LDAC", 96000, 24)) }
        compose.onNodeWithText("系统报告：LDAC 96.0 kHz 24-bit").assertExists()
        compose.runOnIdle { state = state.copy(routeAddress = "BB") }
        compose.onNodeWithText("蓝牙编码：未确认；请在系统设置中检查 LDAC").assertExists()
    }
}
