package com.lin0721.linmusic.core.player.ldac

data class PcmFormat(val sampleRate: Int, val encoding: Int)
data class BluetoothCodecObservation(val address: String, val name: String, val sampleRate: Int?, val bits: Int?)
data class LdacState(
    val routeName: String = "尚未开始播放",
    val bluetoothRoute: Boolean = false,
    val routeAddress: String? = null,
    val codec: BluetoothCodecObservation? = null,
    val decoded: PcmFormat? = null,
    val output: PcmFormat? = null,
    val precisionActive: Boolean = false,
    val fallback: Boolean = false,
    val nativePlayback: Boolean = false,
    val sourceSampleRate: Int? = null,
    val sourceMimeType: String? = null,
    val nativeSessionId: Int? = null
) {
    val matchingCodec: BluetoothCodecObservation? get() = codec?.takeIf {
        bluetoothRoute && !routeAddress.isNullOrBlank() && it.address.equals(routeAddress, ignoreCase = true)
    }
    val confirmedLdac: Boolean get() = matchingCodec?.name == "LDAC"
}

internal fun shouldUseBluetoothPrecision(enabled: Boolean, isA2dp: Boolean, failed: Boolean): Boolean = enabled && isA2dp && !failed
