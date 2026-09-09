package com.lin0721.linmusic.core.player.ldac

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lin0721.linmusic.MainActivity
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NativePrecisionPlayerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ok = false; main { ok = condition() }; if (ok) return
            SystemClock.sleep(40)
        }
        fail("Native player did not reach expected state")
    }
    private fun source(name: String, seconds: Int = 3): File {
        val frames = 48000 * seconds
        val buffer = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(2); putInt(48000); putInt(48000 * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(capacity() - 44)
        }
        return File(context.cacheDir, name).apply { writeBytes(buffer.array()) }
    }
    @Test fun cachedBridgePreservesSequentialReadsSeekSizeAndCacheKey() {
        val bytes = ByteArray(512) { it.toByte() }
        val opens = mutableListOf<DataSpec>()
        val factory = DataSource.Factory {
            val upstream = ByteArrayDataSource(bytes)
            object : DataSource by upstream {
                override fun open(dataSpec: DataSpec): Long { opens += dataSpec; return upstream.open(dataSpec) }
            }
        }
        val source = CachedMediaDataSource(factory, Uri.parse("https://example.test/audio"), "source-key")
        assertEquals(512L, source.getSize())
        val result = ByteArray(20)
        assertEquals(10, source.readAt(0, result, 0, 10))
        assertEquals(10, source.readAt(10, result, 10, 10))
        assertArrayEquals(bytes.copyOf(20), result)
        assertEquals(1, opens.size)
        assertEquals(20, source.readAt(100, result, 0, 20))
        assertArrayEquals(bytes.copyOfRange(100, 120), result)
        assertEquals(100L, opens.last().position)
        assertTrue(opens.all { it.key == "source-key" })
        assertEquals(-1, source.readAt(512, result, 0, 20))
        source.close()
        try { source.readAt(0, result, 0, 1); fail("Closed source accepted read") } catch (_: IOException) { }
    }
    @Test fun rapidSeeksKeepTheLastPositionAndPauseDoesNotResumePlayback() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val file = source("native-seek.wav")
            lateinit var player: NativePrecisionPlayer
            main {
                player = NativePrecisionPlayer(context, DefaultDataSource.Factory(context))
                player.volume = 0f
                player.setMediaItem(MediaItem.Builder().setMediaId("seek").setUri(file.toURI().toString()).build())
                player.prepare()
            }
            try {
                await { player.playbackState == Player.STATE_READY }
                var session: Int? = null
                main { session = player.nativeSessionId; player.prepare() }
                SystemClock.sleep(150)
                main { assertEquals(session, player.nativeSessionId) }
                main { player.seekTo(300); player.seekTo(800); player.seekTo(1500) }
                SystemClock.sleep(400)
                await { player.playbackState == Player.STATE_READY && abs(player.currentPosition - 1500) < 120 }
                main { assertFalse(player.playWhenReady); player.play() }
                await { player.currentPosition > 1750 }
                main { player.pause() }
                SystemClock.sleep(150)
                var paused = 0L; main { paused = player.currentPosition }
                SystemClock.sleep(250)
                main { assertEquals(paused, player.currentPosition); assertFalse(player.isPlaying) }
            } finally { main { player.release() }; file.delete() }
        }
    }
    @Test fun replacingAPreparingSourceCannotPublishTheOldItem() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val first = source("native-slow.wav"); val second = source("native-current.wav")
            val factory = DataSource.Factory {
                val upstream = DefaultDataSource.Factory(context).createDataSource()
                object : DataSource by upstream {
                    override fun open(dataSpec: DataSpec): Long {
                        if (dataSpec.uri.toString().contains("native-slow")) Thread.sleep(300)
                        return upstream.open(dataSpec)
                    }
                }
            }
            lateinit var player: NativePrecisionPlayer
            main {
                player = NativePrecisionPlayer(context, factory); player.volume = 0f
                player.setMediaItem(MediaItem.Builder().setMediaId("obsolete").setUri(first.toURI().toString()).build()); player.prepare()
            }
            SystemClock.sleep(70)
            main { player.setMediaItem(MediaItem.Builder().setMediaId("current").setUri(second.toURI().toString()).build()); player.prepare(); player.play() }
            try {
                await { player.currentPosition > 150 }
                main { assertEquals("current", player.currentMediaItem?.mediaId); assertNull(player.playerError) }
            } finally { main { player.release() }; first.delete(); second.delete() }
        }
    }
    @Test fun naturalCompletionAndSingleRepeatAreReportedCorrectly() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val file = source("native-end.wav", 1)
            lateinit var player: NativePrecisionPlayer
            main {
                player = NativePrecisionPlayer(context, DefaultDataSource.Factory(context)); player.volume = 0f
                player.setMediaItem(MediaItem.fromUri(file.toURI().toString())); player.prepare(); player.play()
            }
            try {
                await { player.playbackState == Player.STATE_ENDED }
                main { player.seekTo(0); player.repeatMode = Player.REPEAT_MODE_ONE; player.play() }
                await { player.isPlaying }
                SystemClock.sleep(1600)
                main { assertEquals(Player.STATE_READY, player.playbackState); assertTrue(player.isPlaying); assertTrue(player.currentPosition < player.duration) }
            } finally { main { player.release() }; file.delete() }
        }
    }
}
