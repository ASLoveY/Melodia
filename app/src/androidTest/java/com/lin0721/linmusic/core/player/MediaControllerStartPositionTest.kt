package com.lin0721.linmusic.core.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaControllerStartPositionTest {

    @Test
    fun generatedWavStartsAtRequestedPositionBeforePrepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wav = File(context.cacheDir, "local-start-position-test.wav")
        writeSilentWav(wav, durationSeconds = 60)

        lateinit var player: ExoPlayer
        lateinit var session: MediaSession
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context).build()
            session = MediaSession.Builder(context, player)
                .setId("LocalStartPositionTest-${System.nanoTime()}")
                .build()
        }
        lateinit var controllerFuture: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            controllerFuture = MediaController.Builder(context, session.token).buildAsync()
        }
        var controller: MediaController? = null

        try {
            controller = controllerFuture.get(10, TimeUnit.SECONDS)
            val mediaItem = MediaItem.Builder()
                .setMediaId("local:${wav.name}")
                .setUri(Uri.fromFile(wav))
                .build()
            instrumentation.runOnMainSync {
                controller!!.startPlayback(
                    mediaItem,
                    PlayMode.LIST_LOOP,
                    startPosition = 30_000L
                )
            }

            val deadline = SystemClock.elapsedRealtime() + 15_000L
            data class Snapshot(val playbackState: Int, val isPlaying: Boolean, val positionMs: Long)
            val snapshot = AtomicReference(Snapshot(Player.STATE_IDLE, false, 0L))
            while (SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    val current = controller!!
                    snapshot.set(Snapshot(current.playbackState, current.isPlaying, current.currentPosition))
                }
                if (snapshot.get().playbackState == Player.STATE_READY && snapshot.get().isPlaying) break
                SystemClock.sleep(50L)
            }

            assertTrue("controller did not become ready", snapshot.get().playbackState == Player.STATE_READY)
            assertTrue("controller did not start playing", snapshot.get().isPlaying)
            assertTrue("playback started before the requested offset", snapshot.get().positionMs >= 30_000L)
        } finally {
            instrumentation.runOnMainSync {
                controller?.release()
                session.release()
                player.release()
            }
            wav.delete()
        }
    }

    private fun writeSilentWav(file: File, durationSeconds: Int) {
        val sampleRate = 8_000
        val channels = 1
        val bitsPerSample = 16
        val dataSize = sampleRate * channels * (bitsPerSample / 8) * durationSeconds
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * channels * bitsPerSample / 8)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()
        file.outputStream().use { output ->
            output.write(header)
            output.write(ByteArray(dataSize))
        }
    }
}
