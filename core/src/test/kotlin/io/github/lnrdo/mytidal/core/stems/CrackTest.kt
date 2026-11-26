package io.github.lnrdo.mytidal.core.stems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** The limiter of the stem player, and the guard that keeps the crack of the drums out of the voice. */
class CrackTest {
    @Test fun theLimiterHoldsLoudSoundUnderTheCeilingWithoutCuttingIt() {
        val rate = 44_100
        val n = rate
        val buffer = FloatArray(n * 2) { i -> (1.8 * sin(2 * PI * 220.0 * (i / 2) / rate)).toFloat() }
        Limiter(rate, ceiling = .95f).process(buffer, n)
        assertTrue(buffer.all { it.isFinite() && abs(it) <= .9501f })
        // It turns the level down, it does not flatten the wave: a hard clip of this sine sits at the ceiling for runs of about 60 samples, a limited one
        // only touches it for a few.
        var run = 0; var longest = 0
        for (i in 0 until n) { if (abs(buffer[2 * i]) > .9495f) { run++; longest = maxOf(longest, run) } else run = 0 }
        assertTrue("longest run at the ceiling: $longest", longest < 16)
    }

    @Test fun theLimiterLeavesQuietSoundAsItIsOnlyLater() {
        val rate = 44_100
        val n = 4410
        val source = FloatArray(n * 2) { i -> (.5 * sin(2 * PI * 440.0 * (i / 2) / rate)).toFloat() }
        val buffer = source.copyOf()
        val limiter = Limiter(rate, lookAheadMs = 1.5f)
        limiter.process(buffer, n)
        val ahead = (rate * 1.5f / 1000f).toInt()
        for (i in ahead until n) assertEquals(source[2 * (i - ahead)], buffer[2 * i], 1e-6f)
    }

    @Test fun aDrumOnsetIsFoundAndASteadyBeatOfTheVoiceIsNot() {
        val leak = DrumLeak()
        val drums = FloatArray(20) { if (it % 8 == 4) 50f else 1f }
        val voice = FloatArray(20) { 1f }
        val out = FloatArray(20)
        leak.amounts(drums, voice, 20, out)
        assertTrue(out[4] > .5f && out[12] > .5f)
        assertEquals(0f, out[2], 0f)
        // The voice rising with the drums keeps what is its own.
        val loudVoice = FloatArray(20) { if (it % 8 == 4) 500f else 1f }
        leak.amounts(drums, loudVoice, 20, out)
        assertTrue(out[4] < .2f)
    }

    private class ArraySource(val x: FloatArray) : StereoSource {
        override val length = x.size
        override fun read(offset: Int, count: Int, left: FloatArray, right: FloatArray) {
            for (i in 0 until count) { val p = offset + i; val v = if (p in x.indices) x[p] else 0f; left[i] = v; right[i] = v }
        }
    }

    private class Sink(n: Int) : StemSink {
        val out = Array(4) { FloatArray(n) }
        override fun write(stem: Int, channel: Int, offset: Int, samples: FloatArray, count: Int) { if (channel == 0) System.arraycopy(samples, 0, out[stem], offset, count) }
    }

    /** Gives the drums twice the voice's estimate everywhere: the voice keeps a fifth of every bin, so a fifth of each snare too, as a leaky mask does. */
    private class Leaky(override val frames: Int, override val bins: Int) : StemNetwork {
        override fun estimate(magnitude: FloatArray) = FloatArray(4 * 2 * frames * bins) { i -> if (i / (2 * frames * bins) == 1) 2f else 1f }
    }

    @Test fun clicksGoToTheDrumsAndTheStemsStillAddUpToTheMix() {
        val n = 44_100 * 3
        // A tone (the voice), and every half a second a click with energy everywhere (the snare).
        val x = FloatArray(n) { i -> (.3 * sin(2 * PI * 330.0 * i / 44_100)).toFloat() + (if (i % 22_050 in 0..40) .8f * (if (i % 2 == 0) 1f else -1f) else 0f) }
        val sink = Sink(n)
        assertTrue(StemSeparator(Leaky(64, 1024), overlap = 8, threads = 1).separate(ArraySource(x), sink))
        var worst = 0f
        for (i in 0 until n) worst = maxOf(worst, abs(sink.out.sumOf { it[i].toDouble() }.toFloat() - x[i]))
        assertEquals(0f, worst, 2e-3f)
        // Around the clicks the voice keeps less of their highs than the fifth that the mask gives it: the guard gave the rest to the drums.
        val guarded = run { val s2 = Sink(n); StemSeparator(Leaky(64, 1024), overlap = 8, threads = 1).separate(ArraySource(FloatArray(n) { i -> if (i % 22_050 in 0..40) .8f * (if (i % 2 == 0) 1f else -1f) else 0f }), s2); s2 }
        fun clickEnergy(x: FloatArray) = (1 until 6).sumOf { k -> (k * 22_050 - 100 until k * 22_050 + 300).sumOf { i -> (x[i] * x[i]).toDouble() } }
        val voice = clickEnergy(guarded.out[Stem.VOCALS.ordinal]); val drums = clickEnergy(guarded.out[Stem.DRUMS.ordinal])
        // Unguarded the voice would have (1/5)^2 / (4/5)^2 = 1/16 of the drums' energy in each click.
        assertTrue("voice $voice drums $drums", voice < drums / 16 * .7)
    }
}
