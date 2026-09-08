package com.lin0721.linmusic.core.player.effects

import kotlin.math.*

/** A block lookahead peak guard. 4x, 32-tap windowed-sinc reconstruction includes block history. */
class TruePeakMeter(private val channels: Int) {
    private val history = FloatArray(32 * channels)
    private val filters = Array(4) { phase ->
        DoubleArray(32) { tap ->
            val x = tap - 15.0 - phase / 4.0
            val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
            sinc * (0.5 - 0.5 * cos(2 * PI * tap / 31))
        }.also { weights -> val total = weights.sum(); for (i in weights.indices) weights[i] /= total }
    }
    fun peak(samples: FloatArray): Double {
        // Include a possible cut to silence: a track/buffer boundary can reconstruct a higher
        // intersample peak than the steady waveform. History alone misses the final half kernel.
        val input = history + samples + FloatArray(history.size)
        var peak = samples.maxOfOrNull { abs(it.toDouble()) } ?: 0.0
        val frames = input.size / channels
        for (frame in 0..frames - 32) for (channel in 0 until channels) for (filter in filters) {
            var value = 0.0
            for (tap in filter.indices) value += input[(frame + tap) * channels + channel] * filter[tap]
            peak = max(peak, abs(value))
        }
        input.copyInto(history, 0, input.size - history.size * 2, input.size - history.size)
        return peak
    }
}

class LoudnessNormalizer(sampleRate: Int, private val channels: Int, cachedLufs: Double? = null) {
    val meter = LoudnessMeter(sampleRate, channels)
    private val peakMeter = TruePeakMeter(channels)
    private val sampleRate = sampleRate
    private val fixedGain = cachedLufs?.let(LoudnessMeter::gainDb)
    var gainDb = fixedGain ?: 0.0; private set
    private var desiredGain = gainDb
    private var lastEstimateSecond = -1L
    private var limiter = 1.0

    fun process(input: FloatArray): FloatArray {
        meter.add(input)
        val seconds = meter.seconds.toLong()
        if (fixedGain == null && meter.validFrames >= sampleRate * 3L && seconds != lastEstimateSecond) {
            meter.integrated()?.let { desiredGain = LoudnessMeter.gainDb(it) }
            lastEstimateSecond = seconds
        }
        val dt = input.size.toDouble() / channels / sampleRate
        val nextGain = fixedGain ?: (gainDb + (desiredGain - gainDb).coerceIn(-.5 * dt, .5 * dt))
        val linear = 10.0.pow(max(gainDb, nextGain) / 20)
        val peak = peakMeter.peak(input) * linear
        // Additional reconstruction margin beyond the requested -1 dBTP ceiling.
        val ceiling = 10.0.pow(-1.6 / 20)
        val required = if (peak > ceiling) ceiling / peak else 1.0
        // Instant lookahead attack; slow release avoids pumping on isolated transients.
        limiter = min(required, limiter + dt / .25)
        val output = FloatArray(input.size)
        val frames = input.size / channels
        for (frame in 0 until frames) {
            val gain = 10.0.pow((gainDb + (nextGain - gainDb) * frame / frames.coerceAtLeast(1)) / 20) * limiter
            for (channel in 0 until channels) {
                val index = frame * channels + channel
                output[index] = (input[index] * gain).coerceIn(-ceiling, ceiling).toFloat()
            }
        }
        gainDb = nextGain
        return output
    }
}
