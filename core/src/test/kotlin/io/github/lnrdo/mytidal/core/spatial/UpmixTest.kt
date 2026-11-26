package io.github.lnrdo.mytidal.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class UpmixTest {
    private val rate = 48_000

    private fun run(channels: Int, mode: SpatialMode, seconds: Double = 1.0, left: (Int) -> Float, right: (Int) -> Float): FloatArray {
        val n = (rate * seconds).toInt()
        val input = FloatArray(n * 2) { i -> if (i % 2 == 0) left(i / 2) else right(i / 2) }
        val out = FloatArray(n * channels)
        val up = Upmixer(rate, channels).also { it.mode = mode }
        // In blocks, as the audio comes.
        var done = 0
        while (done < n) { val k = minOf(480, n - done); up.process(input, done * 2, k, out, done * channels); done += k }
        return out
    }

    /** The energy of channel [k] over the second half of the signal (after the filters settled). */
    private fun energy(out: FloatArray, channels: Int, k: Int): Double {
        var s = 0.0; val frames = out.size / channels
        for (n in frames / 2 until frames) s += out[n * channels + k].toDouble() * out[n * channels + k]
        return s
    }

    private fun tone(hz: Double, n: Int, phase: Double = 0.0) = (.5 * sin(2 * PI * hz * n / rate + phase)).toFloat()

    @Test fun silenceStaysSilent() {
        val out = run(6, SpatialMode.STAGE, left = { 0f }, right = { 0f })
        assertTrue(out.all { it == 0f })
    }

    @Test fun aCentredVoiceGoesToTheCentre() {
        val out = run(8, SpatialMode.STAGE, left = { tone(1000.0, it) }, right = { tone(1000.0, it) })
        val c = energy(out, 8, SpatialChannel.CENTER.ordinal)
        assertTrue("the centre carries it", c > energy(out, 8, SpatialChannel.FRONT_LEFT.ordinal) * 2)
        for (k in listOf(SpatialChannel.BACK_LEFT, SpatialChannel.BACK_RIGHT, SpatialChannel.SIDE_LEFT, SpatialChannel.SIDE_RIGHT)) assertTrue("${k.short} is quiet", energy(out, 8, k.ordinal) < c * .01)
    }

    @Test fun ambienceGoesAroundNotToTheCentre() {
        // Two unrelated tones: nothing is shared.
        val out = run(8, SpatialMode.IMMERSIVE, left = { tone(700.0, it) }, right = { tone(1130.0, it) })
        val around = listOf(SpatialChannel.BACK_LEFT, SpatialChannel.BACK_RIGHT, SpatialChannel.SIDE_LEFT, SpatialChannel.SIDE_RIGHT).sumOf { energy(out, 8, it.ordinal) }
        assertTrue("the room is heard around", around > 0)
        assertTrue("the centre is quiet", energy(out, 8, SpatialChannel.CENTER.ordinal) < around * .2)
    }

    @Test fun anOutOfPhaseSignalLeavesTheCentreEmpty() {
        val out = run(6, SpatialMode.SURROUND, left = { tone(500.0, it) }, right = { -tone(500.0, it) })
        assertEquals(0.0, energy(out, 6, SpatialChannel.CENTER.ordinal), 1e-6)
    }

    @Test fun theBassGoesToTheLowFrequencyChannel() {
        val out = run(6, SpatialMode.STAGE, left = { tone(50.0, it) }, right = { tone(50.0, it) })
        val high = run(6, SpatialMode.STAGE, left = { tone(5000.0, it) }, right = { tone(5000.0, it) })
        assertTrue(energy(out, 6, SpatialChannel.LFE.ordinal) > 100 * energy(high, 6, SpatialChannel.LFE.ordinal))
    }

    @Test fun layoutsHaveTheirChannelCount() {
        assertEquals(6 * 480, run(6, SpatialMode.FOCUS, seconds = .01, left = { .1f }, right = { .1f }).size)
        assertEquals(8 * 480, run(8, SpatialMode.FOCUS, seconds = .01, left = { .1f }, right = { .1f }).size)
    }

    @Test fun modesDifferInWhereTheSoundGoes() {
        fun around(mode: SpatialMode): Double {
            val out = run(8, mode, left = { tone(700.0, it) + tone(2300.0, it, 1.0) / 2 }, right = { tone(1130.0, it) + tone(2300.0, it, 2.0) / 2 })
            return listOf(4, 5, 6, 7).sumOf { energy(out, 8, it) }
        }
        assertTrue(around(SpatialMode.IMMERSIVE) > around(SpatialMode.STAGE))
        assertTrue(around(SpatialMode.STAGE) > around(SpatialMode.FOCUS))
    }

    @Test fun theOutputDoesNotClipOnALoudSignal() {
        val out = run(8, SpatialMode.FOCUS, left = { tone(300.0, it) * 1.9f }, right = { tone(300.0, it) * 1.9f })
        assertTrue(out.all { it in -1.5f..1.5f })
    }

    @Test fun theMetersMove() {
        val up = Upmixer(rate, 6)
        val input = FloatArray(960) { tone(800.0, it / 2) }
        val out = FloatArray(480 * 6)
        for (i in 0 until 20) up.process(input, 0, 480, out, 0)
        assertTrue(up.levels[SpatialChannel.CENTER.ordinal] > 0.05f)
        assertTrue(meterLevel(up.levels[SpatialChannel.CENTER.ordinal]) > meterLevel(up.levels[SpatialChannel.BACK_LEFT.ordinal]))
        assertEquals(0f, meterLevel(0f), 0f)
        assertTrue(sqrt(1f) == 1f)
    }
}
