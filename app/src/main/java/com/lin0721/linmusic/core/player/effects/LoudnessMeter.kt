package com.lin0721.linmusic.core.player.effects

import kotlin.math.*

/** BS.1770 K-weighting and 400 ms blocks with 75% overlap and two-stage gating. */
class LoudnessMeter(val sampleRate: Int, val channels: Int) {
    private class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        var z1 = 0.0; var z2 = 0.0
        fun process(x: Double): Double {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }
    }
    private fun shelf(): Biquad {
        val k = tan(PI * 1681.974450955533 / sampleRate)
        val vh = 10.0.pow(3.999843853973347 / 20)
        val vb = vh.pow(0.4996667741545416)
        val q = 0.7071752369554196
        val a0 = 1 + k / q + k * k
        return Biquad((vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0,
            (vh - vb * k / q + k * k) / a0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
    }
    private fun highPass(): Biquad {
        val k = tan(PI * 38.13547087602444 / sampleRate)
        val q = 0.5003270373238773
        val a0 = 1 + k / q + k * k
        return Biquad(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
    }
    private val shelves = Array(channels) { shelf() }
    private val highPasses = Array(channels) { highPass() }
    private val window = DoubleArray((sampleRate * .4).toInt().coerceAtLeast(1))
    private val hop = (sampleRate * .1).toInt().coerceAtLeast(1)
    private val blocks = ArrayList<Double>()
    private var sum = 0.0
    var frames: Long = 0; private set
    var validFrames: Long = 0; private set
    val seconds: Double get() = frames.toDouble() / sampleRate

    fun add(samples: FloatArray) {
        for (frame in 0 until samples.size / channels) {
            var energy = 0.0
            for (channel in 0 until channels) {
                val value = highPasses[channel].process(shelves[channel].process(samples[frame * channels + channel].toDouble()))
                // Media3's standard channel order: L R C LFE LS RS; exclude LFE, weight surrounds.
                val weight = if (channels >= 6 && channel == 3) 0.0 else if ((channels >= 6 && channel >= 4) || (channels == 5 && channel >= 3)) 1.41 else 1.0
                energy += value * value * weight
            }
            val index = (frames % window.size).toInt()
            sum += energy - window[index]; window[index] = energy; frames++
            if (energy > 1e-7) validFrames++
            if (frames >= window.size && (frames - window.size) % hop == 0L) blocks.add((sum / window.size).coerceAtLeast(0.0))
        }
    }
    fun integrated(): Double? {
        val absolute = blocks.filter { lufs(it) >= -70.0 }
        if (absolute.isEmpty()) return null
        val threshold = lufs(absolute.average()) - 10.0
        val gated = absolute.filter { lufs(it) >= threshold }
        return gated.takeIf { it.isNotEmpty() }?.average()?.let(::lufs)
    }
    companion object {
        fun lufs(energy: Double): Double = if (energy > 0) -0.691 + 10 * log10(energy) else Double.NEGATIVE_INFINITY
        fun gainDb(lufs: Double): Double = (-16.0 - lufs).coerceIn(-24.0, 6.0)
    }
}
