package io.github.yyan115.opentune.core

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastSpectrumTest {
    private fun sine(hz: Double, rate: Int, amplitude: Double = 1.0) = FloatArray(Spectrum.SIZE) { (amplitude * sin(2 * PI * hz * it / rate)).toFloat() }

    @Test fun silenceGivesAnEmptySpectrum() {
        val levels = Spectrum.bands(FloatArray(Spectrum.SIZE), 44_100, 32)
        assertTrue(levels.all { it == 0f })
    }

    @Test fun aToneLightsTheBandThatHoldsIt() {
        val rate = 44_100
        for (hz in listOf(100.0, 440.0, 1_000.0, 4_000.0, 12_000.0)) {
            val levels = Spectrum.bands(sine(hz, rate), rate, 40, 25f, rate / 2f)
            val loudest = levels.indices.maxByOrNull { levels[it] }!!
            val centre = Spectrum.bandHz(loudest, 40, 25f, rate / 2f)
            // A band spans about a seventh of an octave at this resolution, so the loudest band lies within a band of the tone.
            assertTrue("$hz Hz landed at $centre Hz", centre / hz in 0.8..1.25)
            assertTrue("$hz Hz is loud", levels[loudest] > .8f)
        }
    }

    @Test fun aFullScaleToneIsNearZeroDecibels() {
        val power = FloatArray(Spectrum.SIZE / 2)
        Spectrum.powerSpectrum(sine(1_000.0, 44_100), power, FloatArray(Spectrum.SIZE / 2), FloatArray(Spectrum.SIZE / 2))
        val peak = power.max()
        assertEquals("full scale is the reference", 1f, Spectrum.level(peak), .05f)
    }

    @Test fun aQuietToneIsLowerThanALoudOne() {
        val loud = Spectrum.bands(sine(1_000.0, 44_100, 1.0), 44_100, 32).max()
        val quiet = Spectrum.bands(sine(1_000.0, 44_100, .01), 44_100, 32).max()
        assertTrue(quiet < loud - .15f)
    }

    @Test fun theBufferOverloadMatchesTheAllocatingOne() {
        val samples = sine(2_500.0, 48_000)
        val expected = Spectrum.bands(samples, 48_000, 32)
        val half = Spectrum.SIZE / 2
        val actual = Spectrum.bands(samples, 48_000, FloatArray(32), 32, 40f, 16_000f, FloatArray(half), FloatArray(half), FloatArray(half))
        assertTrue(expected.contentEquals(actual))
    }
}
