package com.lin0721.linmusic.core.player.ldac

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothCodecConfig
import android.bluetooth.BluetoothCodecStatus
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Read-only, best-effort system codec observations. No hidden methods or codec forcing. */
class LdacMonitor(private val context: Context) {
    private val _state = MutableStateFlow(LdacState())
    val state = _state.asStateFlow()
    private val observations = mutableMapOf<String, BluetoothCodecObservation>()
    private var started = false
    private val receiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION", "MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (!hasPermission()) { clearObservations(); return }
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED && intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) {
                clearObservations(); return
            }
            val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            val address = device?.address?.lowercase() ?: return
            if (intent.action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) {
                if (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) != BluetoothProfile.STATE_CONNECTED) observations.remove(address)
                refreshCodec(); return
            }
            if (Build.VERSION.SDK_INT < 33 || intent.action != CODEC_ACTION) return
            val config = intent.getParcelableExtra(BluetoothCodecStatus.EXTRA_CODEC_STATUS, BluetoothCodecStatus::class.java)?.codecConfig ?: return
            val name = when (config.codecType) {
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC -> "LDAC"
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC -> "AAC"
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC -> "SBC"
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX -> "aptX"
                BluetoothCodecConfig.SOURCE_CODEC_TYPE_APTX_HD -> "aptX HD"
                else -> "其他编码器"
            }
            val rate = when (config.sampleRate) {
                BluetoothCodecConfig.SAMPLE_RATE_44100 -> 44100
                BluetoothCodecConfig.SAMPLE_RATE_48000 -> 48000
                BluetoothCodecConfig.SAMPLE_RATE_88200 -> 88200
                BluetoothCodecConfig.SAMPLE_RATE_96000 -> 96000
                else -> null
            }
            val bits = when (config.bitsPerSample) {
                BluetoothCodecConfig.BITS_PER_SAMPLE_16 -> 16
                BluetoothCodecConfig.BITS_PER_SAMPLE_24 -> 24
                BluetoothCodecConfig.BITS_PER_SAMPLE_32 -> 32
                else -> null
            }
            observations[address] = BluetoothCodecObservation(address, name, rate, bits)
            refreshCodec()
        }
    }
    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter(CODEC_ACTION).apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED); addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        // Only privileged Bluetooth/system senders can provide codec evidence; ordinary apps
        // cannot spoof the observation even on vendors that do not protect the action string.
        ContextCompat.registerReceiver(context, receiver, filter, Manifest.permission.BLUETOOTH_PRIVILEGED, null, ContextCompat.RECEIVER_EXPORTED)
    }
    fun stop() {
        if (started) { context.unregisterReceiver(receiver); started = false }
        observations.clear(); _state.value = LdacState()
    }
    fun publish(routes: List<AudioDeviceInfo>, decoded: PcmFormat?, output: PcmFormat?, precision: Boolean, fallback: Boolean) {
        if (!hasPermission()) observations.clear()
        val route = routes.singleOrNull()
        val address = if (Build.VERSION.SDK_INT >= 28) route?.address?.takeIf { it.isNotBlank() }?.lowercase() else null
        _state.value = LdacState(
            routeName = if (routes.size > 1) "多个输出设备" else route?.productName?.toString()?.ifBlank { "音频设备" } ?: "等待播放以确认路由",
            bluetoothRoute = route?.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            routeAddress = address, codec = address?.let(observations::get), decoded = decoded, output = output,
            precisionActive = precision, fallback = fallback
        )
    }
    private fun refreshCodec() { _state.value = _state.value.copy(codec = _state.value.routeAddress?.let(observations::get)) }
    private fun clearObservations() { observations.clear(); refreshCodec() }
    companion object {
        private const val CODEC_ACTION = "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED"
    }
}
