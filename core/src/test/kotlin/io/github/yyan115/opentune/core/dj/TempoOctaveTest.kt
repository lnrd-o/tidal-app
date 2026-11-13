package io.github.yyan115.opentune.core.dj

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A tempo that is found at double or at half is put right by where the kick falls. */
class TempoOctaveTest {
    private val rate = 44_100

    /**
     * A groove: a kick on the beats of [kicks] (in a bar of four), a snare on those of [snares], and hats [hatsPerBeat] times to a beat.
     * The hats and the snare are loud, so that they pull the tempo of the whole band toward double.
     */
    private fun groove(bpm: Double, seconds: Double, kicks: Set<Int>, snares: Set<Int>, hatsPerBeat: Int, hat: Float = 0.5f): FloatArray {
        val out = FloatArray((seconds * rate).toInt())
        val period = 60.0 / bpm * rate
        var beat = 0
        var t = 0.2 * rate
        var noise = 99
        while (t < out.size) {
            val inBar = beat % 4
            val start = t.toInt()
            if (inBar in kicks) for (i in 0 until (0.14 * rate).toInt()) if (start + i < out.size) out[start + i] += 0.7f * exp(-i / (0.04f * rate)) * sin(2 * PI * 55.0 * i / rate).toFloat()
            if (inBar in snares) for (i in 0 until (0.12 * rate).toInt()) {
                if (start + i >= out.size) break
                noise = noise * 1103515245 + 12345
                val white = ((noise ushr 16) and 0x7fff) / 16384f - 1f
                out[start + i] += 0.7f * exp(-i / (0.03f * rate)) * (0.5f * white + 0.3f * sin(2 * PI * 180.0 * i / rate).toFloat())
            }
            for (h in 0 until hatsPerBeat) {
                val at = (t + h * period / hatsPerBeat).toInt()
                for (i in 0 until 500) if (at + i < out.size) out[at + i] += hat * (if (i % 2 == 0) 1f else -1f) * exp(-i / 100f)
            }
            t += period; beat++
        }
        return out
    }

    private fun analyze(samples: FloatArray, withKick: Boolean): BeatAnalysis? {
        val env = OnsetEnvelope(rate); env.reset(0)
        env.feed(samples, samples.size)
        val (tail, first) = env.tail(172 * 20)
        return BeatTracker.analyze(tail, env.timeOf(first), rate, kick = if (withKick) env.lowTail(172 * 20) else null)
    }

    @Test fun aSlowSongWithBusyHatsIsNotFoundAtDouble() {
        for (bpm in listOf(78.0, 85.0, 92.0)) {
            val samples = groove(bpm, 30.0, setOf(0, 1, 2, 3), setOf(1, 3), hatsPerBeat = 4)
            val without = analyze(samples, false)!!.bpm; val with = analyze(samples, true)!!.bpm
            println("$bpm bpm: without the kick $without, with the kick $with")
            assertEquals("$bpm bpm with the kick", bpm, with, bpm * 0.01)
        }
    }

    @Test fun aSongWithTheKickOnOneAndThreeKeepsItsBeat() {
        for (bpm in listOf(88.0, 95.0)) {
            val samples = groove(bpm, 30.0, setOf(0, 2), setOf(1, 3), hatsPerBeat = 2)
            val with = analyze(samples, true)!!.bpm
            println("$bpm bpm, kick on one and three: $with")
            assertEquals("$bpm bpm", bpm, with, bpm * 0.01)
        }
    }

    @Test fun aFourOnTheFloorSongKeepsItsTempo() {
        for (bpm in listOf(120.0, 126.0, 132.0)) {
            val samples = groove(bpm, 30.0, setOf(0, 1, 2, 3), setOf(1, 3), hatsPerBeat = 2)
            assertEquals("$bpm bpm", bpm, analyze(samples, true)!!.bpm, bpm * 0.01)
        }
    }

    @Test fun withoutAKickTheTempoIsLeftAsFound() {
        // Only hats and snares: there is no kick to follow, so the result is the same with or without the kick envelope.
        val samples = groove(100.0, 30.0, emptySet(), setOf(1, 3), hatsPerBeat = 2)
        val without = analyze(samples, false); val with = analyze(samples, true)
        assertTrue(without != null && with != null)
        assertEquals(without!!.bpm, with!!.bpm, without.bpm * 0.03)
    }
}
