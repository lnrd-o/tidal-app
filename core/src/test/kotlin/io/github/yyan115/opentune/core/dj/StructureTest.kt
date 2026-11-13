package io.github.yyan115.opentune.core.dj

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The verse, chorus, bridge and peak of a song with harmony. */
class StructureTest {
    private val bpm = 128.0
    private val bar = 4 * 60.0 / bpm

    // Chords as pitch classes.
    private val c = intArrayOf(0, 4, 7); private val am = intArrayOf(9, 0, 4); private val f = intArrayOf(5, 9, 0); private val g = intArrayOf(7, 11, 2)
    private val dm = intArrayOf(2, 5, 9); private val em = intArrayOf(4, 7, 11); private val bb = intArrayOf(10, 2, 5); private val e7 = intArrayOf(4, 8, 11)

    private class Part(val bars: Int, val progression: List<IntArray>, val bass: Double, val loud: Double, val flux: Double, val high: Double)

    private fun slicesOf(parts: List<Part>, noise: Float = 0.015f): List<SliceFeatures> {
        val random = Random(7)
        val out = ArrayList<SliceFeatures>()
        for (part in parts) {
            val count = Math.round(part.bars * bar / SectionAccumulator.SLICE_SECONDS).toInt()
            for (s in 0 until count) {
                val chord = part.progression[((s * SectionAccumulator.SLICE_SECONDS / bar).toInt()) % part.progression.size]
                val chroma = FloatArray(12) { random.nextFloat() * noise }
                for ((k, pitch) in chord.withIndex()) chroma[pitch] += if (k == 0) 1.3f else 1.0f
                val sum = chroma.sum()
                for (k in 0 until 12) chroma[k] /= sum
                out.add(SliceFeatures(part.bass, part.loud, part.flux, -40.0, part.high, chroma))
            }
        }
        return out
    }

    private fun barOf(cue: Cue) = cue.atMs / 1000.0 / bar

    // intro 0-8, verse 8-24, chorus 24-40, verse 40-56, chorus 56-72, bridge 72-80, chorus 80-96, outro 96-104
    private val song = listOf(
        Part(8, listOf(c), -50.0, -38.0, 1.0, -60.0),
        Part(16, listOf(c, am, f, g), -42.0, -30.0, 4.0, -45.0),
        Part(16, listOf(f, g, c, am), -30.0, -22.0, 10.0, -35.0),
        Part(16, listOf(c, am, f, g), -42.0, -30.0, 4.0, -45.0),
        Part(16, listOf(f, g, c, am), -30.0, -22.0, 10.0, -35.0),
        Part(8, listOf(dm, em, bb, e7), -45.0, -32.0, 3.0, -50.0),
        Part(16, listOf(f, g, c, am), -30.0, -22.0, 10.0, -35.0),
        Part(8, listOf(c), -50.0, -38.0, 1.0, -60.0),
    )

    @Test fun theChorusesAreFoundWhereTheHarmonyComesBackLoud() {
        val cues = SectionDetector.detect(slicesOf(song), bpm, 0.0)
        println(cues.joinToString { "${it.kind} bar ${"%.1f".format(barOf(it))}" })
        val choruses = cues.filter { it.kind == CueKind.CHORUS }.map { barOf(it) }
        assertEquals("three choruses: $choruses", 3, choruses.size)
        for ((found, truth) in choruses.zip(listOf(24.0, 56.0, 80.0))) assertEquals(truth, found, 4.0)
    }

    @Test fun theVersesAreTheQuieterGroupThatComesBackFirst() {
        val verses = SectionDetector.detect(slicesOf(song), bpm, 0.0).filter { it.kind == CueKind.VERSE }.map { barOf(it) }
        assertEquals("two verses: $verses", 2, verses.size)
        assertEquals(8.0, verses[0], 4.0); assertEquals(40.0, verses[1], 4.0)
    }

    @Test fun theBridgeIsTheOneOffWithHarmonyOfItsOwn() {
        val bridges = SectionDetector.detect(slicesOf(song), bpm, 0.0).filter { it.kind == CueKind.BRIDGE }.map { barOf(it) }
        assertEquals("one bridge: $bridges", 1, bridges.size)
        assertEquals(72.0, bridges[0], 4.0)
    }

    @Test fun thePeakIsInsideAChorus() {
        val peak = SectionDetector.detect(slicesOf(song), bpm, 0.0).first { it.kind == CueKind.PEAK }
        val bar = barOf(peak)
        assertTrue("peak at bar $bar", bar in 24.0..38.0 || bar in 56.0..70.0 || bar in 80.0..94.0)
    }

    @Test fun aDropAndAChorusAtTheSameMomentAreBothKept() {
        val cues = SectionDetector.detect(slicesOf(song), bpm, 0.0)
        assertEquals("no kind twice at one moment", cues.size, cues.map { it.atMs to it.kind }.toSet().size)
        assertTrue(cues.any { it.kind == CueKind.DROP && cues.any { c -> c.kind == CueKind.CHORUS && c.atMs == it.atMs } })
        assertEquals("in time order", cues.map { it.atMs }, cues.map { it.atMs }.sorted())
    }

    @Test fun aSongWithoutHarmonyOrChangesHasNoStructure() {
        val flat = slicesOf(listOf(Part(64, listOf(c), -30.0, -22.0, 10.0, -35.0)))
        val kinds = SectionDetector.detect(flat, bpm, 0.0).map { it.kind }
        assertTrue("$kinds", CueKind.CHORUS !in kinds && CueKind.BRIDGE !in kinds && CueKind.VERSE !in kinds)
        // A song with no harmony to read gives the energy reading only.
        val silent = List(slicesOf(song).size) { SliceFeatures(-30.0, -22.0, 10.0) }
        assertEquals(listOf(CueKind.INTRO), SectionDetector.detect(silent, bpm, 0.0).map { it.kind })
    }

    // ---- from sound ----

    /** The song again, rendered: each bar a pad of the chord, louder and with a kick and hats in the choruses. */
    private fun render(rate: Int): FloatArray {
        val barFrames = (bar * rate).toInt()
        val out = FloatArray(song.sumOf { it.bars } * barFrames)
        var barIndex = 0
        for (part in song) {
            val loud = part.flux >= 10.0
            val level = if (loud) 0.18 else if (part.flux >= 3.0) 0.12 else 0.07
            for (b in 0 until part.bars) {
                val chord = part.progression[b % part.progression.size]
                val base = (barIndex + b) * barFrames
                for (pitch in chord) {
                    val hz = 220.0 * Math.pow(2.0, ((pitch - 9 + 12) % 12) / 12.0)
                    for (i in 0 until barFrames) out[base + i] += (level * sin(2 * PI * hz * i / rate)).toFloat()
                }
                if (loud) for (beat in 0 until 4) {
                    val start = base + beat * barFrames / 4
                    for (i in 0 until (0.15 * rate).toInt()) if (start + i < out.size) out[start + i] += 0.6f * exp(-i / (0.04f * rate)) * sin(2 * PI * 55.0 * i / rate).toFloat()
                    val hat = start + barFrames / 8
                    for (i in 0 until 400) if (hat + i < out.size) out[hat + i] += 0.08f * (if (i % 2 == 0) 1f else -1f) * exp(-i / 90f)
                }
            }
            barIndex += part.bars
        }
        return out
    }

    @Test fun theStructureOfARenderedSongIsFoundFromItsSound() {
        val rate = 44_100
        val samples = render(rate)
        val envelope = OnsetEnvelope(rate); envelope.reset(0)
        val accumulator = SectionAccumulator(rate)
        envelope.onFrame = { power -> accumulator.add(power, envelope.lastFlux) }
        envelope.feed(samples, samples.size)
        val cues = SectionDetector.detect(accumulator.slices(), bpm, 0.0)
        println("from sound: " + cues.joinToString { "${it.kind} bar ${"%.1f".format(barOf(it))}" })
        val choruses = cues.filter { it.kind == CueKind.CHORUS }.map { barOf(it) }
        assertTrue("choruses $choruses", choruses.size >= 2)
        for (found in choruses) assertTrue("a chorus at bar $found", listOf(24.0, 56.0, 80.0).any { abs(it - found) <= 4.0 })
        val bridge = cues.firstOrNull { it.kind == CueKind.BRIDGE }
        assertNotNull("a bridge", bridge); assertEquals(72.0, barOf(bridge!!), 4.0)
    }
}
