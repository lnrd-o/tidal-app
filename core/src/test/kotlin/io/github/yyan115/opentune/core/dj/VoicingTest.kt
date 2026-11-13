package io.github.yyan115.opentune.core.dj

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The voices of the studio bus, and the curve that the knee plot draws. */
class VoicingTest {
    private val rate = 48_000

    private fun run(config: ElZarConfig, input: FloatArray): FloatArray {
        val elzar = ElZar(rate); elzar.apply(config)
        val out = input.copyOf()
        var frame = 0; val total = out.size / 2
        while (frame < total) { val n = minOf(480, total - frame); elzar.processBlock(out, frame, n); frame += n }
        return out
    }

    private fun energy(buffer: FloatArray, channel: Int, fromFrame: Int, toFrame: Int): Double {
        var sum = 0.0
        for (f in fromFrame until toFrame) sum += buffer[f * 2 + channel] * buffer[f * 2 + channel]
        return sum
    }

    /** One click at the start and silence after it. */
    private fun click(seconds: Double): FloatArray = FloatArray((seconds * rate).toInt() * 2).also { it[0] = 0.8f; it[1] = 0.8f }

    @Test fun theOriginalVoiceLeavesTheBusAsItWas() {
        val input = FloatArray(rate * 2) { i -> (0.4 * sin(2 * PI * 220.0 * (i / 2) / rate)).toFloat() }
        val plain = run(ElZarConfig(on = true, glue = .5f), input)
        val original = run(ElZarConfig(on = true, glue = .5f, voice = Voice.ORIGINAL, voiceA = 1f, voiceB = 1f, voiceC = 1f), input)
        assertTrue(plain.contentEquals(original))
    }

    @Test fun spaceLeavesATailAfterTheSoundHasStopped() {
        val out = run(ElZarConfig(on = true, voice = Voice.SPACE, voiceA = .8f, voiceB = .8f, voiceC = .6f), click(2.0))
        val tail = energy(out, 0, rate / 5, rate); val after = energy(out, 0, rate, rate * 2)
        assertTrue("a tail of $tail", tail > 1e-6)
        assertTrue("and it dies away: $tail then $after", after < tail)
    }

    @Test fun aBiggerSpaceRingsLonger() {
        fun late(size: Float) = energy(run(ElZarConfig(on = true, voice = Voice.SPACE, voiceA = .8f, voiceB = size, voiceC = .6f), click(3.0)), 0, rate, rate * 3)
        assertTrue(late(1f) > late(0f) * 2)
    }

    @Test fun ambienceAddsEarlyReflectionsThatAreGoneQuickerThanTheSpace() {
        val room = run(ElZarConfig(on = true, voice = Voice.AMBIENCE, voiceA = .8f, voiceB = .3f, voiceC = .2f), click(2.0))
        val hall = run(ElZarConfig(on = true, voice = Voice.SPACE, voiceA = .8f, voiceB = .8f, voiceC = .6f), click(2.0))
        assertTrue("the early part is there", energy(room, 0, (rate * .005).toInt() + 1, (rate * .06).toInt()) > 1e-5)
        assertTrue("and the late part is smaller than a hall's", energy(room, 0, rate, rate * 2) < energy(hall, 0, rate, rate * 2))
    }

    @Test fun wideOpensTheSidesAndKeepsTheBassInTheCentre() {
        // A tone with a little of the left only: the high one has sides, the low one is bass.
        fun sideShare(hz: Double, voice: Boolean): Double {
            val input = FloatArray(rate * 4) { i -> val s = (0.3 * sin(2 * PI * hz * (i / 2) / rate)).toFloat(); if (i % 2 == 0) s else s * .6f }
            val out = run(ElZarConfig(on = true, voice = if (voice) Voice.WIDE else Voice.ORIGINAL, voiceA = 1f, voiceB = .5f, voiceC = 0f, glue = if (voice) 0f else 0f), input)
            var side = 0.0; var mid = 0.0
            for (f in rate until rate * 2) { val l = out[f * 2]; val r = out[f * 2 + 1]; side += ((l - r) * .5).let { it * it }; mid += ((l + r) * .5).let { it * it } }
            return side / (mid + 1e-12)
        }
        val highPlain = sideShare(3_000.0, false) ; val highWide = sideShare(3_000.0, true)
        assertTrue("the highs open: $highPlain to $highWide", highWide > highPlain * 3)
        assertTrue("the bass opens much less than the highs: ${sideShare(60.0, true)}", sideShare(60.0, true) < highWide / 4)
    }

    @Test fun everyVoiceStaysFiniteAndInRangeOnLoudMusic() {
        val input = FloatArray(rate * 2) { i -> (0.9 * sin(2 * PI * (100.0 + 7 * (i / 2 % 50)) * (i / 2) / rate)).toFloat() }
        for (voice in Voice.entries) for (amount in listOf(0f, .5f, 1f)) {
            val out = run(ElZarConfig(on = true, glue = .4f, voice = voice, voiceA = amount, voiceB = amount, voiceC = amount), input)
            assertTrue("$voice $amount", out.all { it.isFinite() && abs(it) < 4f })
        }
    }

    @Test fun theKneeCurveBendsAtTheThresholdAndFollowsTheRatioAbove() {
        for (flavor in listOf(Flavor.STUDIO, Flavor.FET, Flavor.VCA)) {
            val knee = kneeOf(flavor, .8f)
            // Far below the threshold the curve is a straight line at the gain of the makeup (only the wet part is turned up).
            val low = knee.outputDb(-60.0) - -60.0
            val lower = knee.outputDb(-50.0) - -50.0
            assertEquals("$flavor below", low, lower, .01)
            // Above the knee, a rise of 10 dB at the input is a rise of 10 / ratio at the processed part, so less than 10 at the output.
            val rise = knee.outputDb(0.0) - knee.outputDb(-10.0)
            assertTrue("$flavor rises $rise", rise in 1.0..9.9)
            assertTrue(knee.angleDegrees in 1.0..45.0)
        }
    }

    @Test fun theLivePointSitsOnTheCurveWhenTheGlueHasSettled() {
        val knee = kneeOf(Flavor.STUDIO, .7f)
        val level = -4.0
        val settled = knee.reductionDb(level)
        assertEquals(knee.outputDb(level), knee.liveOutputDb(level, settled), 1e-9)
        // Before the glue has caught up (no reduction yet) the output is higher than on the curve.
        assertTrue(knee.liveOutputDb(level, 0.0) > knee.outputDb(level))
    }

    @Test fun theLevelThatTheGlueReadsFollowsTheMusic() {
        val elzar = ElZar(rate); elzar.apply(ElZarConfig(on = true, glue = .5f))
        val loud = FloatArray(rate * 2) { i -> (0.5 * sin(2 * PI * 440.0 * (i / 2) / rate)).toFloat() }
        elzar.processBlock(loud, 0, rate)
        val db = 20 * log10(0.5)
        assertEquals("the level is read near the peak", db, elzar.levelDb.toDouble(), 3.0)
        assertTrue(elzar.reductionDb >= 0f)
    }
}
