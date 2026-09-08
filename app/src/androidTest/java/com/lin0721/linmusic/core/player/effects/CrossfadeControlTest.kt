package com.lin0721.linmusic.core.player.effects

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lin0721.linmusic.MainActivity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CrossfadeControlTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6000
        while (SystemClock.elapsedRealtime() < deadline) { var done = false; main { done = condition() }; if (done) return; SystemClock.sleep(25) }
        fail("Player did not reach expected state")
    }
    private fun file(): File {
        val bytes = ByteBuffer.allocate(44 + 16000 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(capacity() - 44)
        }.array()
        return File(context.cacheDir, "crossfade-control.wav").apply { writeBytes(bytes) }
    }
    @Test fun queueInvalidationAndSeekNeverHandoffToStalePreparation() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val file = file()
            lateinit var player: CrossfadePlayer
            var currentGeneration = 1L
            var handoffs = 0
            main {
                player = CrossfadePlayer(context, DefaultDataSource.Factory(context), {}, {}, { _, _ -> handoffs++; true },
                    isTransitionCurrent = { it.generation == currentGeneration })
                player.setMediaItem(MediaItem.Builder().setMediaId("current").setUri(file.toURI().toString()).build())
                player.prepare()
            }
            try {
                await { player.playbackState == Player.STATE_READY }
                main {
                    player.prepareNext(PreparedTransition(1, "current", "obsolete", MediaItem.Builder().setMediaId("obsolete").setUri(file.toURI().toString()).build()))
                    currentGeneration = 2
                    player.play()
                    player.seekTo(2500)
                }
                await { player.playbackState == Player.STATE_ENDED }
                main { assertEquals(0, handoffs); assertEquals("current", player.currentMediaItem?.mediaId) }
            } finally { main { player.release() }; file.delete() }
        }
    }
    @Test fun failedPreparationKeepsCurrentTrackAndRepeatOneNeverCrossfades() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val file = file()
            lateinit var player: CrossfadePlayer
            var handoffs = 0
            var nextCommands = 0
            main {
                player = CrossfadePlayer(context, DefaultDataSource.Factory(context), { nextCommands++ }, {}, { _, _ -> handoffs++; true })
                player.setMediaItem(MediaItem.Builder().setMediaId("current").setUri(file.toURI().toString()).build())
                player.prepare()
            }
            try {
                await { player.playbackState == Player.STATE_READY }
                main {
                    player.prepareNext(PreparedTransition(1, "current", "missing", MediaItem.Builder().setMediaId("missing").setUri("file:///does-not-exist.wav").build()))
                    player.play()
                }
                await { player.currentPosition >= 1000 }
                main {
                    assertNull(player.playerError)
                    player.repeatMode = Player.REPEAT_MODE_ONE
                    player.prepareNext(PreparedTransition(2, "current", "other", MediaItem.Builder().setMediaId("other").setUri(file.toURI().toString()).build()))
                    player.seekTo(3500)
                }
                await { player.currentPosition < 1500 }
                main {
                    assertEquals(0, handoffs)
                    player.seekToNext()
                    assertEquals(1, nextCommands)
                    player.stop()
                }
            } finally { main { player.release() }; file.delete() }
        }
    }
}
