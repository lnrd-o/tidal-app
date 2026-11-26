package io.github.lnrdo.mytidal.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class VirtualizerTest {
    private val rate = 48_000

    /** Renders [seconds] of a signal that only [channel] carries, and returns the left and the right ear. */
    private fun render(channel: SpatialChannel, channels: Int = 8, speakers: Boolean = false, seconds: Double = .5, signal: (Int) -> Float): Pair<FloatArray, FloatArray> {
        val n = (rate * seconds).toInt()
        val input = FloatArray(n * channels)
        for (i in 0 until n) input[i * channels + channel.ordinal] = signal(i)
        val out = FloatArray(n * 2)
        val v = Virtualizer(rate, channels, speakers)
        var done = 0
        while (done < n) { val k = minOf(480, n - done); v.process(input, done * channels, k, out, done * 2); done += k }
        return FloatArray(n) { out[2 * it] } to FloatArray(n) { out[2 * it + 1] }
    }

    private fun energy(x: FloatArray) = x.drop(x.size / 4).sumOf { it.toDouble() * it }
    private fun tone(hz: Double) = { i: Int -> (.5 * sin(2 * PI * hz * i / rate)).toFloat() }
    private val noise = java.util.Random(5).let { r -> FloatArray(48_000) { (r.nextGaussian() * .2).toFloat() } }

    @Test fun silenceStaysSilent() {
        val (l, r) = render(SpatialChannel.FRONT_LEFT) { 0f }
        assertTrue(l.all { it == 0f } && r.all { it == 0f })
    }

    @Test fun aSourceAtTheLeftIsLouderAndEarlierInTheLeftEar() {
        val (l, r) = render(SpatialChannel.SIDE_LEFT) { noise[it % noise.size] }
        assertTrue(energy(l) > 2 * energy(r))
        // The right ear hears it later: its best match with the left ear is some samples on.
        fun match(lag: Int) = (1000 until 20_000).sumOf { l[it].toDouble() * r[it + lag] }
        val best = (0 until 40).maxBy { match(it) }
        assertTrue("delay $best", best in 20..40)
    }

    @Test fun theCentreAndTheBassAreTheSameInBothEars() {
        for (c in listOf(SpatialChannel.CENTER, SpatialChannel.LFE)) {
            val (l, r) = render(c, signal = tone(300.0))
            for (i in l.indices) assertEquals(l[i], r[i], 1e-6f)
        }
    }

    @Test fun aSourceBehindIsDarkerThanTheSameInFront() {
        val (fl, _) = render(SpatialChannel.FRONT_LEFT, signal = tone(8000.0))
        val (bl, _) = render(SpatialChannel.BACK_LEFT, signal = tone(8000.0))
        assertTrue(energy(bl) < energy(fl) * .8)
    }

    @Test fun bothLayoutsWork() {
        val (l, r) = render(SpatialChannel.BACK_RIGHT, channels = 6, signal = tone(1000.0))
        assertTrue(energy(r) > energy(l))
    }

    @Test fun speakersStayStableAndWiden() {
        for (c in SpatialChannel.entries) {
            val (l, r) = render(c, speakers = true, seconds = 1.0) { noise[it % noise.size] }
            assertTrue("${c.short} is finite and bounded", (l + r).all { it.isFinite() && abs(it) < 4f })
        }
        // On speakers what differs between the two sides is raised against what they share: that is what reaches past the speakers.
        fun width(p: Pair<FloatArray, FloatArray>) = energy(FloatArray(p.first.size) { p.first[it] - p.second[it] }) / energy(FloatArray(p.first.size) { p.first[it] + p.second[it] })
        assertTrue(width(render(SpatialChannel.FRONT_LEFT, speakers = true) { noise[it % noise.size] }) > width(render(SpatialChannel.FRONT_LEFT) { noise[it % noise.size] }))
    }
}
