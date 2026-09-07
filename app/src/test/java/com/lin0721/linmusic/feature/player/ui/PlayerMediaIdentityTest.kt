package com.lin0721.linmusic.feature.player.ui

import androidx.media3.common.MediaItem
import com.lin0721.linmusic.core.player.isLocalAudio
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerMediaIdentityTest {

    @Test
    fun `numeric media id is remote`() {
        val item = MediaItem.Builder().setMediaId("12345").build()

        assertFalse(item.isLocalAudio)
    }

    @Test
    fun `local uri media id is local`() {
        val item = MediaItem.Builder().setMediaId("local:content://music/track-1").build()

        assertTrue(item.isLocalAudio)
    }
}
