package io.github.yyan115.opentune.core.dj

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BarTest {
    private val rate = 44_100

    /**
     * A groove: a kick on the beats of [kicks] (counted in a bar of [perBar] beats), a snare on those of [snares], and a hat on every off-beat.
     * The first [skipBeats] beats have no kick, as when a track opens on a snare. Beat 0 of the first bar is at [offset] seconds.
     */
    private fun groove(bpm: Double, seconds: Double, kicks: Set<Int>, snares: Set<Int>, perBar: Int = 4, offset: Double = 0.3, skipBeats: Int = 0): FloatArray {
        val out = FloatArray((seconds * rate).toInt())
        val period = 60.0 / bpm * rate
        var beat = 0
        var t = offset * rate
        var noise = 12345
        while (t < out.size) {
            val inBar = beat % perBar
            val start = t.toInt()
            if (inBar in kicks && beat >= skipBeats) {
                val strength = if (inBar == 0) 1f else 0.75f
                for (i in 0 until (0.14 * rate).toInt()) {
                    if (start + i >= out.size) break
                    out[start + i] += strength * exp(-i / (0.035 * rate)).toFloat() * sin(2 * PI * 55.0 * i / rate).toFloat()
                }
            }
            if (inBar in snares) {
                for (i in 0 until (0.12 * rate).toInt()) {
                    if (start + i >= out.size) break
                    noise = noise * 1103515245 + 12345
                    val white = ((noise ushr 16) and 0x7fff) / 16384f - 1f
                    val tone = sin(2 * PI * 1900.0 * i / rate).toFloat() + sin(2 * PI * 3100.0 * i / rate).toFloat()
                    out[start + i] += 0.7f * exp(-i / (0.03 * rate)).toFloat() * (0.5f * white + 0.25f * tone)
                }
            }
            val hat = (t + period / 2).toInt()
            for (i in 0 until (0.01 * rate).toInt()) if (hat + i < out.size) out[hat + i] += 0.15f * (if (i % 2 == 0) 1f else -1f) * exp(-i / (0.003 * rate)).toFloat()
            t += period; beat++
        }
        return out
    }

    private fun ringOf(samples: FloatArray): PcmRing {
        val ring = PcmRing(samples.size + 8192, rate)
        ring.write(FloatArray(samples.size * 2) { samples[it / 2] }, samples.size)
        return ring
    }

    private fun tracked(bpm: Double, anchorSeconds: Double): BeatAnalysis {
        val period = 60.0 / bpm * rate
        return BeatAnalysis(bpm, period, anchorSeconds * rate, 0, 0.8f)
    }

    /** The distance in seconds of [frame] from the nearest beat of a grid with beat zero at [zero] seconds. */
    private fun fromBeat(frame: Double, zero: Double, bpm: Double): Double {
        val period = 60.0 / bpm
        val x = (frame / rate - zero) / period
        return (x - Math.rint(x)) * period
    }

    @Test fun theGridMovesOntoTheKickWhateverItStartedOn() {
        val bpm = 120.0
        val samples = groove(bpm, 30.0, setOf(0, 2), setOf(1, 3))
        val ring = ringOf(samples)
        val onKick = BarFinder.refine(ring, tracked(bpm, 0.3 + 8 * 0.5), 2.0 * rate)
        // A grid that lay on the off-beat hats, half a beat away, and one that lay just behind the beat.
        val onHats = BarFinder.refine(ring, tracked(bpm, 0.3 + 8 * 0.5 + 0.25), 2.0 * rate)
        val behind = BarFinder.refine(ring, tracked(bpm, 0.3 + 8 * 0.5 + 0.06), 2.0 * rate)
        println("grid from the kick ${fromBeat(onKick.anchorSample, 0.3, bpm) * 1000} ms, from the hats ${fromBeat(onHats.anchorSample, 0.3, bpm) * 1000} ms, from behind ${fromBeat(behind.anchorSample, 0.3, bpm) * 1000} ms")
        assertEquals(fromBeat(onKick.anchorSample, 0.3, bpm), fromBeat(onHats.anchorSample, 0.3, bpm), 0.006)
        assertEquals(fromBeat(onKick.anchorSample, 0.3, bpm), fromBeat(behind.anchorSample, 0.3, bpm), 0.006)
        assertTrue("the grid sits within 30 ms after the kick starts", fromBeat(onKick.anchorSample, 0.3, bpm) in -0.005..0.03)
    }

    @Test fun theBarStartsOnTheKickAndNotOnTheSnare() {
        val bpm = 120.0
        val samples = groove(bpm, 36.0, setOf(0, 2), setOf(1, 3))
        val ring = ringOf(samples)
        val grid = BarFinder.refine(ring, tracked(bpm, 0.3 + 9 * 0.5), 1.0 * rate)
        assertTrue("the bar is known", grid.barKnown); assertEquals(4, grid.beatsPerBar)
        // The beats of the bar that the finder calls the first: their distance from the true bar one must be a whole number of two beats.
        val down = grid.nextDownbeat(6.0 * rate) / rate
        val bars = (down - 0.3) / (4 * 0.5)
        val beatsFromBarOne = (down - 0.3) / 0.5
        println("a downbeat at $down s, $beatsFromBarOne beats from the true bar one")
        // Whatever the finder calls the first beat, it must carry a kick (an even beat), and never a snare.
        assertEquals(0.0, beatsFromBarOne - 2 * Math.rint(beatsFromBarOne / 2), 0.08)
        assertTrue(bars.isFinite())
    }

    @Test fun aTrackThatOpensOnASnareStillStartsItsPhraseOnTheKickBeforeIt() {
        val bpm = 120.0
        // The first bar has no kick on the one: the first strong hit is the snare on beat 2 of the first bar, at 0.8 s.
        val samples = groove(bpm, 36.0, setOf(0, 2), setOf(1, 3), skipBeats = 1)
        val ring = ringOf(samples)
        val snareHit = (0.3 + 0.5) * rate
        val grid = BarFinder.refine(ring, tracked(bpm, 0.3 + 9 * 0.5), 0.7 * rate, snareHit)
        val origin = BarFinder.origin(grid, snareHit) / rate
        println("origin at $origin s (the snare is at 0.8 s, the kick before it at 0.3 s)")
        assertEquals("the phrase starts on the beat before the snare", 0.3, origin, 0.03)
    }

    @Test fun twoTracksWithDifferentStartsGetBarsThatMeetKickOnKick() {
        val bpm = 120.0
        val a = groove(bpm, 36.0, setOf(0, 2), setOf(1, 3), offset = 0.3)
        val b = groove(bpm, 36.0, setOf(0, 2), setOf(1, 3), offset = 0.62, skipBeats = 1)
        val gridA = BarFinder.refine(ringOf(a), tracked(bpm, 0.3 + 9 * 0.5), 0.2 * rate, 0.3 * rate)
        val gridB = BarFinder.refine(ringOf(b), tracked(bpm, 0.62 + 9 * 0.5), 0.5 * rate, (0.62 + 0.5) * rate)
        val originA = BarFinder.origin(gridA, 0.3 * rate) / rate
        val originB = BarFinder.origin(gridB, (0.62 + 0.5) * rate) / rate
        println("origins: A $originA s, B $originB s")
        assertEquals("A starts on its first kick", 0.3, originA, 0.03)
        assertEquals("B starts on the kick before its first snare, not on the snare", 0.62, originB, 0.03)
    }

    @Test fun aBarOfThreeBeatsIsRecognised() {
        val bpm = 108.0
        val samples = groove(bpm, 40.0, setOf(0), setOf(1, 2), perBar = 3)
        val grid = BarFinder.refine(ringOf(samples), tracked(bpm, 0.3 + 10 * 60.0 / bpm), 1.0 * rate)
        assertEquals(3, grid.beatsPerBar)
        assertTrue(grid.barKnown)
        val four = BarFinder.refine(ringOf(groove(bpm, 40.0, setOf(0, 2), setOf(1, 3))), tracked(bpm, 0.3 + 10 * 60.0 / bpm), 1.0 * rate)
        assertEquals(4, four.beatsPerBar)
    }

    @Test fun tracksWithDifferentBarsAreNotMatchedOnTheBeat() {
        val a = BeatAnalysis(120.0, 60.0 / 120.0 * rate, 1000.0, 0, 0.8f, beatsPerBar = 4, barKnown = true)
        val b = BeatAnalysis(120.0, 60.0 / 120.0 * rate, 1000.0, 0, 0.8f, beatsPerBar = 3, barKnown = true)
        assertEquals(MixKind.SOFT, MixPlanner.decide(TrackSummary(a, "8A", 5.0), TrackSummary(b, "8A", 5.0), rate, rate).kind)
        assertEquals(MixKind.BEAT, MixPlanner.decide(TrackSummary(a, "8A", 5.0), TrackSummary(a, "8A", 5.0), rate, rate).kind)
        assertTrue(abs(MixPlanner.decide(TrackSummary(a, "8A", 5.0), TrackSummary(b, "8A", 5.0), rate, rate).match) <= 1.0)
    }

    @Test fun theMixerLeavesNoStepAtTheEdgesOfAMix() {
        val rate = 48_000
        val mixer = DjMixer(rate)
        val a = PcmRing(60 * rate, rate); val b = PcmRing(60 * rate, rate)
        // Two low tones, so that any step shows as a jump between neighbouring samples.
        a.write(FloatArray(2 * 40 * rate) { i -> (0.4 * sin(2 * PI * 90.0 * (i / 2) / rate)).toFloat() }, 40 * rate)
        b.write(FloatArray(2 * 40 * rate) { i -> (0.4 * sin(2 * PI * 110.0 * (i / 2) / rate)).toFloat() }, 40 * rate)
        mixer.setSource(0, RingSource { a }); mixer.setSource(1, RingSource { b })
        mixer.setPlan(MixPlan(0, 1, 5.0 * rate, 2.0 * rate, 6 * rate, 1.0, MixKind.BEAT, 4, 4, 0.5))
        val out = FloatArray(2 * 480)
        var previous = 0f
        var biggest = 0f
        var rendered = 0
        while (rendered < 14 * rate) {
            mixer.render(out, 480)
            for (i in 0 until 480) {
                val v = out[i * 2]
                if (rendered + i > 0) biggest = maxOf(biggest, abs(v - previous))
                previous = v
            }
            rendered += 480
        }
        println("the biggest step between neighbouring samples: $biggest")
        // A 110 Hz tone of 0.4 changes by at most 0.4 * 2 * PI * 110 / 48000 = 0.006 per sample. Allow for the two tones and the filters.
        assertTrue("a step of $biggest", biggest < 0.03f)
    }

    @Test fun aKeyGivenByNameBecomesACamelotCode() {
        assertEquals("7B", Camelot.fromName("F", "MAJOR"))
        assertEquals("8A", Camelot.fromName("A", "MINOR"))
        assertEquals("6B", Camelot.fromName("Bb", "MAJOR"))
        assertEquals("11A", Camelot.fromName("FSharp", "MINOR"))
        assertEquals("11A", Camelot.fromName("F#", "minor"))
        assertEquals("5B", Camelot.fromName("EFlat", "MAJOR"))
        assertEquals(null, Camelot.fromName("", "MAJOR"))
        assertEquals(null, Camelot.fromName("H", "MAJOR"))
    }
}
