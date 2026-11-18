package io.github.yyan115.opentune.core.dj

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the analysis of a song costs on the processor, and whether songs analysed side by side on several cores go faster. It prints its numbers, so
 * that a change to the analysis can be weighed, and it checks only that the analysis is far faster than the music.
 */
class AnalysisCostTest {
    private val rate = 44_100

    private fun song(seconds: Int, bpm: Double): FloatArray {
        val out = FloatArray(seconds * rate)
        val period = 60.0 / bpm * rate
        var t = 0.0; var n = 7
        while (t < out.size) {
            val at = t.toInt()
            for (i in 0 until (0.12 * rate).toInt()) if (at + i < out.size) out[at + i] += 0.6f * exp(-i / (0.04f * rate)) * sin(2 * PI * 55.0 * i / rate).toFloat()
            for (i in 0 until 600) if (at + i + (period / 2).toInt() < out.size) { n = n * 1103515245 + 12345; out[at + i + (period / 2).toInt()] += 0.2f * ((n ushr 16 and 0x7fff) / 16384f - 1f) * exp(-i / 120f) }
            t += period
        }
        for (i in out.indices) out[i] += 0.1f * sin(2 * PI * 220.0 * i / rate).toFloat()
        return out
    }

    /** All the work that a deck does for a song: the spectrum, the onsets, the key, the slices for the cues, a tempo estimate every 2 s, and the cues. */
    private fun analyze(samples: FloatArray, estimateEvery: Double = 2.0): Long {
        val began = System.nanoTime()
        val envelope = OnsetEnvelope(rate); envelope.reset(0)
        val keys = KeyDetector(rate)
        val sections = SectionAccumulator(rate)
        var frame = 0
        envelope.onFrame = { power -> if (++frame % 4 == 0) keys.add(power); sections.add(power, envelope.lastFlux) }
        val chunk = (rate * estimateEvery).toInt()
        var fed = 0
        var grid: BeatAnalysis? = null
        val buffer = FloatArray(chunk)
        while (fed < samples.size) {
            val n = minOf(chunk, samples.size - fed)
            System.arraycopy(samples, fed, buffer, 0, n)
            envelope.feed(buffer, n); fed += n
            if (fed >= 10 * rate) {
                val fps = rate.toDouble() / OnsetEnvelope.HOP
                val (tail, first) = envelope.tail((fps * 20).toInt())
                grid = BeatTracker.analyze(tail, envelope.timeOf(first), rate, kick = envelope.lowTail((fps * 20).toInt())) ?: grid
            }
        }
        keys.result()
        grid?.let { SectionDetector.detect(sections.slices(), it.bpm, 0.0) }
        return System.nanoTime() - began
    }

    @Test fun theAnalysisIsFarFasterThanTheMusicAndScalesOverCores() {
        val seconds = 180
        val songs = List(4) { song(seconds, 118.0 + it * 4) }
        repeat(2) { analyze(songs[0]) } // warm up the compiler
        val one = analyze(songs[0])
        val rarely = analyze(songs[0], estimateEvery = 20.0)
        val perSongMs = one / 1e6
        println("one song of $seconds s: %.0f ms (%.0f times faster than the music); with an estimate every 20 s: %.0f ms".format(perSongMs, seconds * 1000 / perSongMs, rarely / 1e6))
        // Four songs one after another, then four at once on as many threads.
        var t = System.nanoTime(); for (s in songs) analyze(s); val serial = (System.nanoTime() - t) / 1e6
        val pool = Executors.newFixedThreadPool(4)
        t = System.nanoTime()
        songs.map { s -> pool.submit<Long> { analyze(s) } }.forEach { it.get() }
        val parallel = (System.nanoTime() - t) / 1e6
        pool.shutdown(); pool.awaitTermination(10, TimeUnit.SECONDS)
        println("four songs: %.0f ms one after another, %.0f ms side by side on %d cores (%.1f times faster)".format(serial, parallel, Runtime.getRuntime().availableProcessors(), serial / parallel))
        assertTrue("the analysis must be far faster than real time", perSongMs < seconds * 1000 / 5.0)
    }
}
