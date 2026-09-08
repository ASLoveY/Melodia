package com.lin0721.linmusic.core.player.effects

import com.lin0721.linmusic.core.preferences.PlaybackEffectsSettings
import kotlin.math.*
import org.junit.Assert.*
import org.junit.Test

class AudioEffectsTest {
    private val rate = 48000
    private fun tone(seconds: Double, amplitude: Double, frequency: Double = 1000.0, channels: Int = 2): FloatArray =
        FloatArray((seconds * rate).toInt() * channels) { (amplitude * sin(2 * PI * frequency * (it / channels) / rate)).toFloat() }

    @Test fun defaultsAreThreeSecondsAndEnabled() {
        assertEquals(3000L, PlaybackEffectsSettings().durationMs)
        assertTrue(PlaybackEffectsSettings().crossfadeEnabled)
        assertTrue(PlaybackEffectsSettings().normalizationEnabled)
    }
    @Test fun complementaryEnvelopeHasNoExtraGainAndClampsTime() {
        assertEquals(0f, CrossfadeEnvelope.incoming(-10, 3000), 0f)
        assertEquals(.5f, CrossfadeEnvelope.incoming(1500, 3000), .00001f)
        assertEquals(1f, CrossfadeEnvelope.incoming(4000, 3000), 0f)
        (0..3000 step 10).forEach { assertEquals(1f, CrossfadeEnvelope.incoming(it.toLong(), 3000) + CrossfadeEnvelope.incoming(3000L - it, 3000), .00001f) }
    }
    @Test fun unknownAndShortDurationsDoNotConsumeMostOfASong() {
        assertEquals(0L, CrossfadeEnvelope.duration(3000, -1, 10000))
        assertEquals(500L, CrossfadeEnvelope.duration(3000, 1000, 10000))
        assertEquals(1000L, CrossfadeEnvelope.duration(3000, 10000, 2000))
    }
    @Test fun bs1770StereoOneKhzReferenceAndAbsoluteGate() {
        val meter = LoudnessMeter(rate, 2)
        meter.add(tone(3.0, .1))
        assertEquals(-20.0, meter.integrated()!!, .15)
        val silent = LoudnessMeter(rate, 2)
        silent.add(FloatArray(rate * 2))
        assertNull(silent.integrated())
    }
    @Test fun relativeGateExcludesLongSilentPassages() {
        val meter = LoudnessMeter(rate, 2)
        meter.add(tone(4.0, .1)); meter.add(FloatArray(rate * 2 * 4))
        assertEquals(-20.0, meter.integrated()!!, .4)
    }
    @Test fun cachedGainReachesTargetWithinOneLuAndPreservesDynamics() {
        val normalizer = LoudnessNormalizer(rate, 2, -20.0)
        val input = tone(.4, .1) + tone(.4, .05)
        val output = normalizer.process(input)
        val measured = LoudnessMeter(rate, 2)
        measured.add(output.copyOfRange(0, rate * 2 * 4 / 10))
        assertEquals(-16.0, measured.integrated()!!, 1.0)
        fun energy(from: Int, to: Int) = output.sliceArray(from until to).sumOf { it.toDouble() * it }
        assertEquals(4.0, energy(0, output.size / 2) / energy(output.size / 2, output.size), .01)
    }
    @Test fun firstPlayGainSlewNeverExceedsHalfDbPerSecond() {
        val normalizer = LoudnessNormalizer(rate, 2)
        repeat(12) {
            val before = normalizer.gainDb
            normalizer.process(tone(.5, .02))
            assertTrue(abs(normalizer.gainDb - before) <= .25001)
            if (it < 5) assertEquals(0.0, normalizer.gainDb, 0.0)
        }
        assertTrue(normalizer.gainDb > 0)
    }
    @Test fun silenceNeverGetsBoosted() {
        val normalizer = LoudnessNormalizer(rate, 2)
        repeat(10) { assertTrue(normalizer.process(FloatArray(rate)).all { sample -> sample == 0f }) }
        assertEquals(0.0, normalizer.gainDb, 0.0)
    }
    @Test fun reconstructedPeakIsLimitedIncludingIntersampleOvershoot() {
        val normalizer = LoudnessNormalizer(rate, 2, -30.0)
        val output = normalizer.process(tone(.2, .99, 12000.0))
        val peak = TruePeakMeter(2).peak(output)
        assertTrue("peak=$peak", peak <= 10.0.pow(-1.0 / 20))
    }
    @Test fun gainLimitsAreConservative() {
        assertEquals(6.0, LoudnessMeter.gainDb(-60.0), 0.0)
        assertEquals(-24.0, LoudnessMeter.gainDb(20.0), 0.0)
    }
}
