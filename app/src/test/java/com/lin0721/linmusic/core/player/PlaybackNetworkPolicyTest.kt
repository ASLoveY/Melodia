package com.lin0721.linmusic.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackNetworkPolicyTest {
    @Test
    fun `only actively playing remote media is governed by network policy`() {
        assertTrue(shouldApplyNetworkPlaybackPolicy(isPlaying = true, isLocalAudio = false))
        assertFalse(shouldApplyNetworkPlaybackPolicy(isPlaying = true, isLocalAudio = true))
        assertFalse(shouldApplyNetworkPlaybackPolicy(isPlaying = false, isLocalAudio = false))
        assertFalse(shouldApplyNetworkPlaybackPolicy(isPlaying = false, isLocalAudio = true))
    }
}
