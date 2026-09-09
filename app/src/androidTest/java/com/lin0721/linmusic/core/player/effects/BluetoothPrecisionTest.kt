package com.lin0721.linmusic.core.player.effects

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lin0721.linmusic.MainActivity
import com.lin0721.linmusic.core.player.ldac.LdacMonitor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BluetoothPrecisionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < until) { var ready = false; main { ready = condition() }; if (ready) return; SystemClock.sleep(50) }
        fail("Precision output did not reach expected state")
    }
    private fun source(): File {
        val frames = 96000 * 6
        val data = ByteBuffer.allocate(44 + frames * 6).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(capacity() - 8); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(2); putInt(96000); putInt(96000 * 6); putShort(6); putShort(24)
            put("data".toByteArray()); putInt(capacity() - 44)
            repeat(frames) { frame ->
                val value = (sin(2 * PI * 1000 * frame / 96000) * 500000).toInt() or 1
                repeat(2) { put(value.toByte()); put((value shr 8).toByte()); put((value shr 16).toByte()) }
            }
        }.array()
        return File(context.cacheDir, "precision-24bit-96khz.wav").apply { writeBytes(data) }
    }
    @Test fun precisionUsesFloat96kAndSwitchesBackWithoutResumingPausedAudio() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val file = source(); val monitor = LdacMonitor(context)
            lateinit var player: CrossfadePlayer
            main {
                player = CrossfadePlayer(context, DefaultDataSource.Factory(context), {}, {}, { _, _ -> true }, ldacMonitor = monitor, bluetoothRouteOverride = { true })
                player.setMediaItem(MediaItem.Builder().setMediaId("test").setUri(file.toURI().toString()).build())
                player.prepare(); player.play()
            }
            try {
                await { player.currentPosition > 300 }
                main { player.pause() }; SystemClock.sleep(150)
                var position = 0L
                main { position = player.currentPosition; player.setBluetoothPrecisionRequested(true) }
                await { player.highPrecisionActive && player.playbackState == Player.STATE_READY && monitor.state.value.output?.encoding == C.ENCODING_PCM_FLOAT }
                main {
                    assertEquals(96000, monitor.state.value.output?.sampleRate)
                    assertTrue(abs(player.currentPosition - position) < 50)
                    assertFalse(player.playWhenReady)
                    assertTrue(player.effects.normalizationEnabled)
                    player.setBluetoothPrecisionRequested(false)
                }
                await { !player.highPrecisionActive && player.playbackState == Player.STATE_READY && monitor.state.value.output?.encoding == C.ENCODING_PCM_16BIT }
                main { assertFalse(player.playWhenReady); assertTrue(player.effects.crossfadeEnabled) }
            } finally { main { player.release() }; file.delete() }
        }
    }
    @Test fun unsupportedFloatTrackFallsBackOnceForTheSameSong() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val file = source(); val monitor = LdacMonitor(context)
            lateinit var player: CrossfadePlayer
            var attempts = 0
            main {
                player = CrossfadePlayer(context, DefaultDataSource.Factory(context), {}, {}, { _, _ -> true }, ldacMonitor = monitor,
                    bluetoothRouteOverride = { true }, precisionTrackProvider = DefaultAudioSink.AudioTrackProvider { _, _, _ -> attempts++; throw IllegalArgumentException("unsupported test format") })
                player.setMediaItem(MediaItem.Builder().setMediaId("failed").setUri(file.toURI().toString()).build())
                player.prepare(); player.play()
            }
            try {
                await { player.currentPosition > 200 }
                main { player.setBluetoothPrecisionRequested(true) }
                await { monitor.state.value.fallback && player.playbackState == Player.STATE_READY && !player.highPrecisionActive }
                val afterFailure = attempts
                SystemClock.sleep(800)
                main { assertEquals(afterFailure, attempts); assertTrue(player.isPlaying); assertNull(player.playerError) }
            } finally { main { player.release() }; file.delete() }
        }
    }
}
