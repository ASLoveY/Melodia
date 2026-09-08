package com.lin0721.linmusic.core.player.effects

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.test.core.app.ApplicationProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.File
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LoudnessAudioProcessorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun disabledProcessingIsBitExactAndEmptyInputIsHarmless() {
        val processor = LoudnessAudioProcessor(LoudnessProfileStore(context))
        processor.flush()
        processor.enabled = false
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)); processor.flush()
        processor.queueInput(AudioProcessor.EMPTY_BUFFER)
        val input = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).apply { repeat(8) { putShort((it * 100 - 350).toShort()) }; flip() }
        processor.queueInput(input)
        val output = processor.output
        repeat(8) { assertEquals((it * 100 - 350).toShort(), output.short) }
        assertFalse(input.hasRemaining())
        processor.reset()
    }
    @Test fun changingTheLocalFileInvalidatesTheStoredProfile() = runBlocking {
        val file = File(context.cacheDir, "loudness-identity-test").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val key = localAudioFingerprint(context, Uri.fromFile(file).toString())
            val store = LoudnessProfileStore(context)
            val profile = TrackLoudnessProfile(key, -20.0, 3000)
            store.save(profile)
            assertEquals(profile, LoudnessProfileStore(context).read(key))
            file.appendBytes(byteArrayOf(4))
            val changed = localAudioFingerprint(context, Uri.fromFile(file).toString())
            assertNotEquals(key, changed)
            assertNull(store.read(changed))
        } finally { file.delete() }
    }
}
