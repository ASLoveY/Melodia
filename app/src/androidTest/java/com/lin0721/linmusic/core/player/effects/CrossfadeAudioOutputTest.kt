package com.lin0721.linmusic.core.player.effects

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lin0721.linmusic.core.preferences.PlaybackEffectsSettings
import com.lin0721.linmusic.validation.AudioCaptureActivity
import com.lin0721.linmusic.validation.AudioCaptureService
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
class CrossfadeAudioOutputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(timeoutMs: Long = 12000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) { if (condition()) return; SystemClock.sleep(50) }
        fail("Timed out; capture error=${AudioCaptureService.failure}")
    }
    private fun clickStart(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.text?.toString() in listOf("Start now", "Start recording", "Start", "Share screen", "立即开始", "开始录制")) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            return node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
        for (i in 0 until node.childCount) if (clickStart(node.getChild(i))) return true
        return false
    }
    private fun wav(name: String, frequency: Double): File {
        val rate = 48000
        val frames = rate * 8
        val data = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(frames * 2)
            repeat(frames) { putShort((sin(2 * PI * frequency * it / rate) * 6000).toInt().toShort()) }
        }.array()
        return File(context.cacheDir, name).apply { writeBytes(data) }
    }

    @Test fun renderedAudioContainsBothTonesForThreeSecondsAndHandoffKeepsPosition() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        context.startActivity(Intent(context, AudioCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await { clickStart(instrumentation.uiAutomation.rootInActiveWindow); AudioCaptureService.ready }
        val first = wav("crossfade-440.wav", 440.0)
        val second = wav("crossfade-880.wav", 880.0)
        lateinit var player: CrossfadePlayer
        var created = false
        var handoffs = 0
        var handoffPosition = 0L
        try {
            main {
                player = CrossfadePlayer(context, DefaultDataSource.Factory(context), {}, {}, { _, position ->
                    handoffs++; handoffPosition = position; true
                })
                created = true
                player.effects = PlaybackEffectsSettings(true, 3, false)
                player.setMediaItem(MediaItem.Builder().setMediaId("first").setUri(first.toURI().toString()).build())
                player.prepare(); player.play()
            }
            await { var ready = false; main { ready = player.playbackState == Player.STATE_READY }; ready }
            main { player.prepareNext(PreparedTransition(1, "first", "second", MediaItem.Builder().setMediaId("second").setUri(second.toURI().toString()).build())) }
            await { var midFade = false; main { midFade = player.currentPosition >= 6200 }; midFade }
            var pausedAt = 0L
            main { player.pause(); pausedAt = player.currentPosition }
            SystemClock.sleep(600)
            main {
                assertTrue("Pause should stop promptly", abs(player.currentPosition - pausedAt) <= 50)
                pausedAt = player.currentPosition
            }
            val silenceStart = AudioCaptureService.snapshot().size
            SystemClock.sleep(400)
            val afterPause = AudioCaptureService.snapshot()
            val pausedCapture = afterPause.copyOfRange(silenceStart, afterPause.size)
            val pausedSamples = ByteBuffer.wrap(pausedCapture).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            var maximumDuringPause = 0
            while (pausedSamples.hasRemaining()) maximumDuringPause = max(maximumDuringPause, abs(pausedSamples.get().toInt()))
            assertTrue("Both decks must pause, peak=$maximumDuringPause", maximumDuringPause <= 2)
            main { assertEquals(pausedAt, player.currentPosition); player.play() }
            await(15000) { var done = false; main { done = handoffs == 1 }; done }
            SystemClock.sleep(500)
            main {
                assertEquals("second", player.currentMediaItem?.mediaId)
                assertTrue("handoff=$handoffPosition", handoffPosition in 2600..3400)
                assertTrue(player.currentPosition >= handoffPosition)
                player.pause()
            }
            val pcm = AudioCaptureService.snapshot()
            File(context.getExternalFilesDir(null), "crossfade-output.pcm").writeBytes(pcm)
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "crossfade-output.pcm")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/Melodia-validation")
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                }
                context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                    context.contentResolver.openOutputStream(uri)?.use { it.write(pcm) }
                }
            }
            val shorts = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val samples = ShortArray(shorts.remaining()).also { shorts.get(it) }
            val window = 4800 // 100 ms, stereo
            fun amplitude(startFrame: Int, frequency: Double): Double {
                var re = 0.0; var im = 0.0
                for (i in 0 until window) {
                    val sample = samples[(startFrame + i) * 2].toDouble()
                    val phase = 2 * PI * frequency * i / 48000
                    re += sample * cos(phase); im += sample * sin(phase)
                }
                return hypot(re, im) * 2 / window
            }
            val amplitudes = (0 until samples.size / 2 - window step window).map { amplitude(it, 440.0) to amplitude(it, 880.0) }
            val maximum = amplitudes.maxOf { max(it.first, it.second) }
            assertTrue("No rendered audio captured", maximum > 100)
            val overlap = amplitudes.count { it.first > maximum * .03 && it.second > maximum * .03 } * .1
            println("AUDIO_CAPTURE overlapSeconds=$overlap handoffPositionMs=$handoffPosition peakDuringPause=$maximumDuringPause")
            assertTrue("captured overlap=$overlap", overlap in 2.2..3.4)
            assertTrue(amplitudes.any { it.first > it.second * 5 && it.first > maximum * .5 })
            assertTrue(amplitudes.any { it.second > it.first * 5 && it.second > maximum * .5 })
        } finally {
            main { if (created) player.release() }
            context.stopService(Intent(context, AudioCaptureService::class.java))
            first.delete(); second.delete()
        }
    }
}
