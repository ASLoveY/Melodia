package com.lin0721.linmusic.core.player.ldac

import org.junit.Assert.*
import org.junit.Test

class LdacStateTest {
    @Test fun codecMustBelongToTheActualBluetoothRoute() {
        val codec = BluetoothCodecObservation("AA:BB", "LDAC", 96000, 24)
        val correct = LdacState(bluetoothRoute = true, routeAddress = "aa:bb", codec = codec)
        assertTrue(correct.confirmedLdac)
        assertFalse(correct.copy(bluetoothRoute = false).confirmedLdac)
        assertFalse(correct.copy(routeAddress = "CC:DD").confirmedLdac)
        assertFalse(correct.copy(routeAddress = null).confirmedLdac)
        assertFalse(correct.copy(codec = null).confirmedLdac)
        assertFalse(correct.copy(codec = codec.copy(name = "AAC")).confirmedLdac)
    }
    @Test fun noPrecisionSwitchWithoutUserRequestAndActualA2dp() {
        assertTrue(shouldUseBluetoothPrecision(true, true, false))
        assertFalse(shouldUseBluetoothPrecision(false, true, false))
        assertFalse(shouldUseBluetoothPrecision(true, false, false))
        assertFalse(shouldUseBluetoothPrecision(true, true, true))
    }
}
