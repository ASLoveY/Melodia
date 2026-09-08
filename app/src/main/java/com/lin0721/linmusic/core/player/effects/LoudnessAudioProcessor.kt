package com.lin0721.linmusic.core.player.effects

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.roundToInt

@androidx.annotation.OptIn(UnstableApi::class)
class LoudnessAudioProcessor(private val profiles: LoudnessProfileStore) : BaseAudioProcessor() {
    @Volatile var enabled = true
        set(value) { if (field != value) discontinuity(); field = value }
    @Volatile private var sourceKey = ""
    @Volatile private var cached: TrackLoudnessProfile? = null
    @Volatile private var completeCandidate: TrackLoudnessProfile? = null
    @Volatile private var continuous = false
    private var normalizer: LoudnessNormalizer? = null

    fun beginSource(key: String, startPositionMs: Long) {
        sourceKey = key; cached = profiles.read(key); completeCandidate = null
        continuous = startPositionMs == 0L
    }
    fun discontinuity() { continuous = false; completeCandidate = null }
    fun complete(durationMs: Long) {
        val candidate = completeCandidate ?: return
        if (continuous && sourceKey.isNotBlank() && durationMs > 0 && kotlin.math.abs(candidate.durationMs - durationMs) <= 250 && candidate.sourceKey == sourceKey) profiles.save(candidate)
        completeCandidate = null
    }
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }
    override fun onFlush() {
        if (inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0) { normalizer = null; return }
        normalizer = LoudnessNormalizer(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, cached?.integratedLufs)
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (!enabled) { output.put(inputBuffer); output.flip(); return }
        val input = FloatArray(inputBuffer.remaining() / 2) { inputBuffer.short / 32768f }
        val processor = normalizer
        val processed = try { processor?.process(input) ?: input } catch (_: Exception) { input }
        processed.forEach { output.putShort((it * 32768).roundToInt().coerceIn(-32768, 32767).toShort()) }
        output.flip()
    }
    override fun onQueueEndOfStream() {
        if (!enabled || !continuous) return
        normalizer?.meter?.let { meter -> meter.integrated()?.let {
            completeCandidate = TrackLoudnessProfile(sourceKey, it, (meter.seconds * 1000).toLong())
        } }
    }
    override fun onReset() { normalizer = null; completeCandidate = null }
}
