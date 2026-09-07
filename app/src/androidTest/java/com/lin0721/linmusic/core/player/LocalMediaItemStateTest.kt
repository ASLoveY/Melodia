package com.lin0721.linmusic.core.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Regression test for MediaController dropping localConfiguration across the service boundary. */
@RunWith(AndroidJUnit4::class)
class LocalMediaItemStateTest {

    @Test
    fun metadataLocalUriSurvivesWhenLocalConfigurationIsAbsent() {
        val uri = "content://media/audio/42"
        val mediaItem = MediaItem.Builder()
            .setMediaId("local:$uri")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setExtras(Bundle().apply { putString("localUri", uri) })
                    .build()
            )
            .build()

        assertNull(mediaItem.localConfiguration)
        assertEquals(uri, mediaItem.localAudioUri())
    }
}
