package io.github.yyan115.opentune.core.dj

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionsTest {
    private val bpm = 128.0
    private val bar = 4 * 60.0 / bpm   // 1.875 s

    private fun slicesOf(structure: List<Triple<Int, SliceFeatures, SliceFeatures>>): List<SliceFeatures> {
        // Each part: its number of bars, and its features at the start and at the end (they move in a straight line).
        val out = ArrayList<SliceFeatures>()
        for ((bars, from, to) in structure) {
            val count = (bars * bar / SectionAccumulator.SLICE_SECONDS).toInt()
            for (i in 0 until count) {
                val f = i / count.toDouble()
                out.add(SliceFeatures(from.bassDb + (to.bassDb - from.bassDb) * f, from.loudDb + (to.loudDb - from.loudDb) * f, from.flux + (to.flux - from.flux) * f))
            }
        }
        return out
    }

    private val quiet = SliceFeatures(-50.0, -36.0, 1.0)
    private val rising = SliceFeatures(-50.0, -28.0, 6.0)
    private val loud = SliceFeatures(-24.0, -20.0, 12.0)
    private val mid = SliceFeatures(-48.0, -30.0, 3.0)

    private fun barOf(cue: Cue) = cue.atMs / 1000.0 / bar

    @Test fun theSectionsOfADanceTrackAreFound() {
        val slices = slicesOf(listOf(
            Triple(16, quiet, quiet), Triple(8, quiet, rising), Triple(16, loud, loud), Triple(8, mid, mid), Triple(16, loud, loud), Triple(16, quiet, quiet),
        ))
        val cues = SectionDetector.detect(slices, bpm, 0.0)
        println(cues.joinToString { "${it.kind} bar ${"%.1f".format(barOf(it))}" })
        fun near(kind: CueKind, bar: Double, count: Int = 1): Cue {
            val found = cues.filter { it.kind == kind }
            assertEquals("$kind count", count, found.size)
            val nearest = found.minByOrNull { abs(barOf(it) - bar) }!!
            assertTrue("$kind at bar ${barOf(nearest)}, expected $bar", abs(barOf(nearest) - bar) <= 1.0)
            return nearest
        }
        near(CueKind.INTRO, 0.0)
        near(CueKind.BUILD, 16.0)
        assertEquals("two drops", 2, cues.count { it.kind == CueKind.DROP })
        assertTrue(abs(barOf(cues.first { it.kind == CueKind.DROP }) - 24.0) <= 1.0)
        assertTrue(abs(barOf(cues.last { it.kind == CueKind.DROP }) - 48.0) <= 1.0)
        near(CueKind.BREAK, 40.0)
        near(CueKind.OUTRO, 64.0)
        assertTrue("sorted", cues.map { it.atMs } == cues.map { it.atMs }.sorted())
    }

    @Test fun aSongThatDoesNotChangeHasNoSections() {
        val flat = slicesOf(listOf(Triple(64, loud, loud)))
        val cues = SectionDetector.detect(flat, bpm, 0.0)
        assertEquals(listOf(CueKind.INTRO), cues.map { it.kind })
        assertTrue(SectionDetector.detect(emptyList(), bpm, 0.0).isEmpty())
        // A short snippet has too few bars to have sections.
        assertEquals(listOf(CueKind.INTRO), SectionDetector.detect(slicesOf(listOf(Triple(12, quiet, loud))), bpm, 0.0).map { it.kind })
    }

    @Test fun theCuesStartFromTheOriginOfTheFirstPhrase() {
        val lead = (3.0 / SectionAccumulator.SLICE_SECONDS).toInt()
        val slices = List(lead) { quiet } + slicesOf(listOf(Triple(16, quiet, quiet), Triple(16, loud, loud), Triple(16, quiet, quiet)))
        val cues = SectionDetector.detect(slices, bpm, 3.0)
        val intro = cues.first { it.kind == CueKind.INTRO }
        assertEquals(3000L, intro.atMs)
        val drop = cues.first { it.kind == CueKind.DROP }
        assertEquals("the drop is 16 bars after the origin", 3.0 + 16 * bar, drop.atMs / 1000.0, bar)
    }

    @Test fun aShortLoudFlickerIsNotASection() {
        // Two loud bars inside a quiet stretch are merged away.
        val slices = slicesOf(listOf(Triple(20, quiet, quiet), Triple(2, loud, loud), Triple(14, quiet, quiet), Triple(16, loud, loud), Triple(12, quiet, quiet)))
        val drops = SectionDetector.detect(slices, bpm, 0.0).filter { it.kind == CueKind.DROP }
        assertEquals(1, drops.size)
        assertTrue("the drop is the long one: bar ${barOf(drops[0])}", abs(barOf(drops[0]) - 36.0) <= 1.0)
    }

    /** A track built from sound: a quiet intro of hats and a pad, a drop with a kick and a bass, a break with only the pad, and a second drop. */
    private fun synth(rate: Int): FloatArray {
        val barFrames = (bar * rate).toInt()
        val structure = listOf(16 to false, 16 to true, 8 to false, 16 to true, 16 to false)
        val out = FloatArray(structure.sumOf { it.first } * barFrames)
        var barIndex = 0
        for ((bars, kick) in structure) {
            for (b in 0 until bars) {
                val base = (barIndex + b) * barFrames
                for (i in 0 until barFrames) out[base + i] += (0.03 * sin(2 * PI * 330.0 * i / rate)).toFloat()
                for (beat in 0 until 4) {
                    val start = base + (beat * barFrames / 4)
                    val hat = start + barFrames / 8
                    for (i in 0 until 400) if (hat + i < out.size) out[hat + i] += 0.06f * (if (i % 2 == 0) 1f else -1f) * exp(-i / 90f)
                    if (kick) for (i in 0 until (0.15 * rate).toInt()) if (start + i < out.size) out[start + i] += 0.8f * exp(-i / (0.04f * rate)) * sin(2 * PI * 55.0 * i / rate).toFloat()
                }
                if (kick) for (i in 0 until barFrames) out[base + i] += (0.25 * sin(2 * PI * 49.0 * i / rate)).toFloat()
            }
            barIndex += bars
        }
        return out
    }

    @Test fun theSectionsOfARenderedTrackAreFoundFromItsSound() {
        val rate = 44_100
        val samples = synth(rate)
        val envelope = OnsetEnvelope(rate); envelope.reset(0)
        val accumulator = SectionAccumulator(rate)
        envelope.onFrame = { power -> accumulator.add(power, envelope.lastFlux) }
        envelope.feed(samples, samples.size)
        println("heard ${accumulator.seconds} s of ${samples.size / rate} s")
        assertTrue(accumulator.seconds > samples.size / rate - 2.0)
        val cues = SectionDetector.detect(accumulator.slices(), bpm, 0.0)
        println(cues.joinToString { "${it.kind} bar ${"%.1f".format(barOf(it))}" })
        val drops = cues.filter { it.kind == CueKind.DROP }
        assertEquals("two drops", 2, drops.size)
        assertEquals(16.0, barOf(drops[0]), 1.5); assertEquals(40.0, barOf(drops[1]), 1.5)
        val breakCue = cues.firstOrNull { it.kind == CueKind.BREAK }
        assertNotNull(breakCue); assertEquals(32.0, barOf(breakCue!!), 1.5)
        val outro = cues.firstOrNull { it.kind == CueKind.OUTRO }
        assertNotNull(outro); assertEquals(56.0, barOf(outro!!), 1.5)
    }
}
