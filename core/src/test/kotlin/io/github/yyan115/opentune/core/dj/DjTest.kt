package io.github.yyan115.opentune.core.dj

import io.github.yyan115.opentune.core.Spectrum
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjTest {
    /** A synthetic track: a kick on every beat, a hat on the off-beats, and a louder kick on the first beat of each bar. */
    private fun track(bpm: Double, seconds: Double, rate: Int, offset: Double = 0.0): FloatArray {
        val out = FloatArray((seconds * rate).toInt())
        val period = 60.0 / bpm * rate
        var beat = 0
        var t = offset * rate
        while (t < out.size) {
            val strength = if (beat % 4 == 0) 1f else 0.6f
            val start = t.toInt()
            for (i in 0 until (0.12 * rate).toInt()) {
                if (start + i >= out.size) break
                val env = exp(-i / (0.03 * rate)).toFloat()
                out[start + i] += strength * env * sin(2 * PI * 55.0 * i / rate).toFloat()
            }
            val hat = (t + period / 2).toInt()
            for (i in 0 until (0.01 * rate).toInt()) if (hat + i < out.size) out[hat + i] += 0.25f * (if (i % 2 == 0) 1f else -1f) * exp(-i / (0.003 * rate)).toFloat()
            t += period; beat++
        }
        return out
    }

    private fun analyze(samples: FloatArray, rate: Int): BeatAnalysis? {
        val env = OnsetEnvelope(rate)
        env.reset(0)
        env.feed(samples, samples.size)
        val (tail, first) = env.tail(172 * 20)
        return BeatTracker.analyze(tail, env.timeOf(first), rate)
    }

    @Test fun theTempoOfAClickTrackIsFoundWithinAFewThousandths() {
        for ((bpm, rate) in listOf(120.0 to 44_100, 126.0 to 48_000, 92.0 to 44_100, 140.0 to 48_000)) {
            val beat = analyze(track(bpm, 24.0, rate), rate)
            assertNotNull("$bpm bpm", beat)
            assertEquals("$bpm bpm at $rate Hz", bpm, beat!!.bpm, bpm * 0.004)
            assertTrue("confidence ${beat.confidence}", beat.confidence > MixPlanner.MIN_CONFIDENCE)
        }
    }

    @Test fun theBeatGridLandsOnTheBeats() {
        val rate = 44_100
        val bpm = 124.0
        val samples = track(bpm, 24.0, rate, offset = 0.21)
        val beat = analyze(samples, rate)!!
        val period = 60.0 / bpm * rate
        // The distance of the anchor from the nearest true beat, in milliseconds. A constant bias is the same for every track.
        val trueBeat = 0.21 * rate
        var error = (beat.anchorSample - trueBeat) % period
        if (error > period / 2) error -= period
        if (error < -period / 2) error += period
        println("bias: ${error / rate * 1000} ms")
        assertTrue("grid is ${error / rate * 1000} ms from the beats", abs(error / rate * 1000) < 40)
    }

    @Test fun twoTracksWithTheSameGridAgreeOnTheirBias() {
        val a = analyze(track(124.0, 24.0, 44_100, 0.0), 44_100)!!
        val b = analyze(track(124.0, 24.0, 48_000, 0.0), 48_000)!!
        val period = 60.0 / 124.0
        // Both grids start at 0, so the anchors, in seconds, differ by whole beats plus the bias difference of the two sample rates.
        var d = (a.anchorSample / 44_100 - b.anchorSample / 48_000) % period
        if (d > period / 2) d -= period
        if (d < -period / 2) d += period
        assertTrue("biases differ by ${d * 1000} ms", abs(d) < 0.004)
    }

    @Test fun theDownbeatIsTheStrongerKick() {
        val rate = 44_100
        val beat = analyze(track(120.0, 24.0, rate), rate)!!
        // The first beat of each bar is the strongest, so downbeats are every fourth beat from the true bar starts.
        val period = beat.periodSamples
        val downbeat = beat.nextDownbeat(beat.anchorSample - 20 * period)
        val barLength = 4 * period
        val phase = (downbeat % barLength) / barLength
        // The bar starts at multiples of 2 seconds, plus the constant bias, so the phase must be close to 0 (or 1).
        assertTrue("phase $phase", phase < 0.05 || phase > 0.95)
    }

    @Test fun silenceHasNoBeat() {
        val rate = 44_100
        val beat = analyze(FloatArray(rate * 20), rate)
        assertTrue(beat == null || beat.confidence < MixPlanner.MIN_CONFIDENCE)
    }

    private fun chord(rate: Int, notes: List<Double>, seconds: Double): FloatArray =
        FloatArray((seconds * rate).toInt()) { i -> notes.sumOf { sin(2 * PI * it * i / rate) }.toFloat() / notes.size }

    private fun key(samples: FloatArray, rate: Int): KeyResult? {
        val detector = KeyDetector(rate)
        val window = FloatArray(Spectrum.SIZE); val power = FloatArray(Spectrum.SIZE / 2); val re = FloatArray(Spectrum.SIZE / 2); val im = FloatArray(Spectrum.SIZE / 2)
        var at = 0
        while (at + Spectrum.SIZE <= samples.size) {
            System.arraycopy(samples, at, window, 0, Spectrum.SIZE)
            Spectrum.powerSpectrum(window, power, re, im)
            detector.add(power)
            at += 4096
        }
        return detector.result()
    }

    @Test fun aMajorChordProgressionIsRecognisedAsItsCamelotKey() {
        val rate = 44_100
        // C major scale tones weighted toward the tonic triad: C4 E4 G4 plus C5, G5, E5.
        val tones = listOf(261.63, 329.63, 392.0, 523.25, 659.25, 783.99, 293.66, 349.23)
        val c = key(chord(rate, tones.take(6) + listOf(261.63, 392.0, 523.25), 12.0), rate)
        assertNotNull(c)
        assertEquals("8B", c!!.camelot)
    }

    @Test fun aMinorTriadIsRecognisedAsAMinor() {
        val rate = 44_100
        val a = key(chord(rate, listOf(220.0, 261.63, 329.63, 440.0, 523.25, 659.25, 220.0, 440.0), 12.0), rate)
        assertNotNull(a)
        assertEquals("8A", a!!.camelot)
    }

    @Test fun camelotCompatibility() {
        assertEquals(1.0, Camelot.compatibility("8A", "8A"), 0.0)
        assertEquals(0.9, Camelot.compatibility("8A", "9A"), 0.0)
        assertEquals(0.9, Camelot.compatibility("12A", "1A"), 0.0)
        assertEquals(0.8, Camelot.compatibility("8A", "8B"), 0.0)
        assertEquals(0.5, Camelot.compatibility("8A", "10A"), 0.0)
        assertEquals(0.1, Camelot.compatibility("8A", "3B"), 0.0)
        assertEquals("8B", Camelot.code(0, false)); assertEquals("8A", Camelot.code(9, true)); assertEquals("5A", Camelot.code(0, true))
    }

    @Test fun theEqSeparatesTheBands() {
        val rate = 48_000
        fun gainDb(frequency: Double, low: Double, mid: Double, high: Double): Double {
            val eq = ThreeBandEq(rate)
            var inSum = 0.0; var outSum = 0.0
            for (i in 0 until rate) {
                val x = sin(2 * PI * frequency * i / rate).toFloat()
                val y = eq.processLeft(x, low, mid, high)
                if (i > rate / 2) { inSum += x * x; outSum += y * y }
            }
            return 10 * Math.log10(outSum / inSum)
        }
        for (f in listOf(60.0, 100.0, 1000.0, 5000.0, 12000.0)) assertTrue("$f Hz flat: ${gainDb(f, 1.0, 1.0, 1.0)}", abs(gainDb(f, 1.0, 1.0, 1.0)) < 1.0)
        assertTrue("bass killed", gainDb(60.0, 0.0, 1.0, 1.0) < -30)
        assertTrue("mids stay with the bass killed", gainDb(1000.0, 0.0, 1.0, 1.0) > -1.0)
        assertTrue("highs killed", gainDb(8000.0, 1.0, 1.0, 0.0) < -30)
        assertTrue("mids killed", gainDb(1000.0, 1.0, 0.0, 1.0) < -30)
    }

    @Test fun theRingKeepsFramesAndWraps() {
        val ring = PcmRing(1000, 48_000)
        val block = FloatArray(600) { it.toFloat() }
        ring.write(block, 300); ring.write(block, 300); ring.write(block, 300)
        assertEquals(900L, ring.written)
        assertEquals(block[0], ring.left(600), 0f)
        ring.markDiscontinuity()
        assertEquals(900L, ring.discontinuity)
        assertTrue(ring.oldest() >= 900L)
    }

    @Test fun theCurvesHandTheBassOverAtTheSwapAndEndSilent() {
        val g = DoubleArray(6)
        for (swap in listOf(0.5, 2.0 / 3.0)) {
            MixCurves.gains(0.0, swap, g)
            assertEquals(1.0, g[0], 1e-9); assertEquals(0.0, g[3], 1e-9); assertEquals(0.0, g[4], 1e-9)
            MixCurves.gains(swap - 0.05, swap, g)
            assertEquals(1.0, g[0], 1e-9); assertEquals(0.0, g[3], 1e-9)
            MixCurves.gains(swap + 0.05, swap, g)
            assertEquals(0.0, g[0], 1e-9); assertEquals(1.0, g[3], 1e-9)
            MixCurves.gains(1.0, swap, g)
            assertEquals(0.0, g[1], 1e-9); assertEquals(0.0, g[2], 1e-9); assertEquals(1.0, g[4], 1e-9); assertEquals(1.0, g[5], 1e-9)
        }
        assertEquals(1.04, MixCurves.speed(0.3, 1.04, 0.5), 1e-9); assertEquals(1.0, MixCurves.speed(1.0, 1.04, 0.5), 1e-9)
        // The low-pass on the incoming track opens during the blend and is wide open at the swap. The high-pass on the outgoing one closes after it.
        assertTrue(MixCurves.lowpassHz(0.0, 0.5) < 300.0); assertTrue(MixCurves.lowpassHz(0.5, 0.5) >= SweepFilter.OPEN_HZ)
        assertTrue(MixCurves.highpassHz(0.5, 0.5) <= SweepFilter.CLOSED_HZ); assertTrue(MixCurves.highpassHz(1.0, 0.5) > 3000.0)
        assertTrue(MixCurves.lowpassHz(0.2, 0.5) < MixCurves.lowpassHz(0.3, 0.5))
    }

    @Test fun theSweepFiltersPassOrCutTheRightBands() {
        val rate = 48_000
        fun level(highpass: Boolean, cutoff: Double, frequency: Double): Double {
            val filter = SweepFilter(rate, highpass)
            filter.setCutoff(cutoff)
            var inSum = 0.0; var outSum = 0.0
            for (i in 0 until rate) {
                val x = sin(2 * PI * frequency * i / rate).toFloat()
                val y = filter.process(0, x)
                if (i > rate / 2) { inSum += x * x; outSum += y * y }
            }
            return 10 * Math.log10(outSum / inSum)
        }
        assertTrue("low-pass keeps the bass: ${level(false, 1000.0, 100.0)}", level(false, 1000.0, 100.0) > -2.0)
        assertTrue("low-pass cuts the highs: ${level(false, 1000.0, 8000.0)}", level(false, 1000.0, 8000.0) < -25.0)
        assertTrue("high-pass keeps the highs: ${level(true, 1000.0, 8000.0)}", level(true, 1000.0, 8000.0) > -2.0)
        assertTrue("high-pass cuts the bass: ${level(true, 1000.0, 100.0)}", level(true, 1000.0, 100.0) < -25.0)
        assertEquals("open filter does nothing", 0.0, level(false, 20_000.0, 5000.0), 1e-9)
        assertEquals("closed filter does nothing", 0.0, level(true, 10.0, 5000.0), 1e-9)
    }

    @Test fun aNearerTempoScoresHigherEvenWhenTheBeatCannotBeMatched() {
        val a = BeatAnalysis(124.0, 60.0 / 124.0 * 44_100, 1000.0, 0, 0.8f)
        val nearer = BeatAnalysis(110.0, 60.0 / 110.0 * 44_100, 500.0, 0, 0.8f)
        val farther = BeatAnalysis(90.0, 60.0 / 90.0 * 44_100, 500.0, 0, 0.8f)
        val x = MixPlanner.decide(TrackSummary(a, "8A", 5.0), TrackSummary(nearer, "8A", 5.0), 44_100, 44_100)
        val y = MixPlanner.decide(TrackSummary(a, "8A", 5.0), TrackSummary(farther, "8A", 5.0), 44_100, 44_100)
        assertEquals(MixKind.SOFT, x.kind); assertEquals(MixKind.SOFT, y.kind)
        assertTrue("${x.match} vs ${y.match}", x.match > y.match + 0.03)
    }

    @Test fun theDecisionMatchesBeatsWithinSixPercentWhenEnergiesAreClose() {
        val a = BeatAnalysis(124.0, 60.0 / 124.0 * 44_100, 1000.0, 0, 0.8f)
        val near = BeatAnalysis(126.0, 60.0 / 126.0 * 44_100, 500.0, 0, 0.8f)
        val far = BeatAnalysis(100.0, 60.0 / 100.0 * 44_100, 500.0, 0, 0.8f)
        val weak = BeatAnalysis(124.0, 60.0 / 124.0 * 44_100, 500.0, 0, 0.1f)
        val d = MixPlanner.decide(TrackSummary(a, "8A", 7.0), TrackSummary(near, "9A", 8.0), 44_100, 44_100)
        assertEquals(MixKind.BEAT, d.kind)
        // The incoming track is faster, so it is slowed down: its beat period is shorter.
        assertEquals(124.0 / 126.0, d.speedIncoming, 1e-9)
        assertEquals(0.9, d.keyScore, 0.0)
        assertEquals("close energies allow the long blend", 32, d.blendBeats)
        assertTrue("match ${d.match}", d.match > 0.7)
        assertEquals("a bigger gap shortens the blend", 16, MixPlanner.decide(TrackSummary(a, "8A", 3.0), TrackSummary(near, "8A", 6.5), 44_100, 44_100).blendBeats)
        assertEquals("a huge gap is not matched on the beat", MixKind.SOFT, MixPlanner.decide(TrackSummary(a, "8A", 9.0), TrackSummary(near, "8A", 3.0), 44_100, 44_100).kind)
        assertEquals(MixKind.SOFT, MixPlanner.decide(TrackSummary(a, "8A", 5.0), TrackSummary(far, "8A", 5.0), 44_100, 44_100).kind)
        assertEquals(MixKind.SOFT, MixPlanner.decide(TrackSummary(a, "8A", 5.0), TrackSummary(weak, "8A", 5.0), 44_100, 44_100).kind)
        assertEquals(MixKind.SOFT, MixPlanner.decide(TrackSummary(null, null), TrackSummary(near, null), 44_100, 44_100).kind)
        val unknown = MixPlanner.decide(TrackSummary(a, "8A"), TrackSummary(near, "8A"), 44_100, 44_100)
        assertEquals("unknown energy still mixes", MixKind.BEAT, unknown.kind)
    }

    @Test fun energyRisesWithLoudnessAndDensity() {
        val rate = 44_100
        val loud = PcmRing(10 * rate, rate); val quiet = PcmRing(10 * rate, rate)
        loud.write(FloatArray(2 * 8 * rate) { i -> (0.6 * sin(2 * PI * 80.0 * (i / 2) / rate)).toFloat() }, 8 * rate)
        quiet.write(FloatArray(2 * 8 * rate) { i -> (0.03 * sin(2 * PI * 80.0 * (i / 2) / rate)).toFloat() }, 8 * rate)
        val busy = Energy.score(Energy.rmsDb(loud, 0, 8L * rate), 14.0)
        val calm = Energy.score(Energy.rmsDb(quiet, 0, 8L * rate), 1.0)
        println("energy: busy $busy calm $calm")
        assertTrue("busy $busy calm $calm", busy > calm + 4)
        assertTrue(busy <= 10.0 && calm >= 1.0)
    }

    @Test fun theFirstDrumHitStartsThePhraseAndSnapsToTheGrid() {
        val rate = 44_100
        // Eight seconds of pad, then drums at 120 bpm. The first drum hit is at 8 s.
        val samples = FloatArray(30 * rate)
        for (i in 0 until 8 * rate) samples[i] = (0.05 * sin(2 * PI * 220.0 * i / rate)).toFloat()
        val drums = track(120.0, 22.0, rate)
        for (i in drums.indices) samples[8 * rate + i] += drums[i]
        val env = OnsetEnvelope(rate); env.reset(0); env.feed(samples, samples.size)
        val beat = analyze(samples, rate)!!
        val head = env.head(172 * 24)!!
        val origin = PhraseOrigin.detect(head, env.timeOf(0), beat, rate)
        assertNotNull(origin)
        val seconds = origin!! / rate
        println("origin at $seconds s")
        assertTrue("origin at $seconds s", abs(seconds - 8.0) < 0.06)
    }

    @Test fun theKickLockMeasuresTheShiftOfAGrid() {
        val rate = 44_100
        val samples = track(124.0, 24.0, rate, offset = 0.0)
        val ring = PcmRing(30 * rate, rate)
        val stereo = FloatArray(samples.size * 2) { samples[it / 2] }
        ring.write(stereo, samples.size)
        val beat = analyze(samples, rate)!!
        val from = 6.0 * rate
        val base = KickAligner.offsetSeconds(ring, beat.anchorSample, beat.periodSamples, from)!!
        val shifted = KickAligner.offsetSeconds(ring, beat.anchorSample + 0.010 * rate, beat.periodSamples, from)!!
        println("kick offset $base s, with the grid 10 ms later $shifted s")
        // A grid 10 ms later puts the kick 10 ms earlier against it.
        assertEquals(-0.010, shifted - base, 0.0015)
        assertTrue("the kick sits within 25 ms of the grid: $base", abs(base) < 0.025)
    }

    @Test fun theSwapFallsOnAPhraseBoundaryOfTheOutgoingTrack() {
        val rate = 44_100
        val period = 0.5 * rate                       // 120 bpm
        val a = TrackSummary(BeatAnalysis(120.0, period, 90.0 * rate + 0.1 * rate, 0, 0.9f), "8A", 6.0, origin = 10.0 * rate)
        val b = TrackSummary(BeatAnalysis(120.0, period, 3.0 * rate, 0, 0.9f), "8A", 6.0, origin = 3.0 * rate)
        val decision = MixDecision(MixKind.BEAT, 1.0, 1.0, 120.0, 120.0, 6.0, 6.0, 1.0, 1.0, 32)
        val pos = 100.0 * rate
        val plan = MixScheduler.schedule(0, 1, decision, a, b, rate, rate, 48_000, pos, 200.0 * rate, 0.0)
        assertNotNull(plan)
        plan!!
        assertEquals(32, plan.blendBeats); assertEquals(16, plan.tailBeats)
        assertEquals(32.0 / 48.0, plan.swap, 1e-9)
        assertTrue(plan.startOutgoing >= pos + MixScheduler.LEAD_SECONDS * rate)
        // The swap is 32 beats after the start, and a whole number of 32-beat blocks after the first drum hit.
        val swapFrame = plan.startOutgoing + 32 * period
        val blocks = (swapFrame - a.origin!!) / (32 * period)
        assertEquals("swap at block $blocks", Math.rint(blocks), blocks, 1e-6)
        // The incoming track enters at its own first drum hit, so that its swap is 32 beats into its first phrase.
        assertEquals(3.0 * rate, plan.startIncoming, 1e-6)
        assertEquals(48.0 * 0.5 * 48_000, plan.lengthOut.toDouble(), 2.0)
        // The blend never starts before the earliest moment, and the tail ends before the track does.
        val end = plan.startOutgoing + plan.lengthOut.toDouble() * rate / 48_000
        assertTrue(end <= 200.0 * rate - MixScheduler.TAIL_SECONDS * rate)
        // With little room, the long blend gives way to the short one.
        val tight = MixScheduler.schedule(0, 1, decision, a, b, rate, rate, 48_000, pos, pos + 38.0 * rate, 0.0)
        assertNotNull(tight)
        assertTrue("blend ${tight!!.blendBeats}", tight.blendBeats == 16 || tight.blendBeats == 32)
        val boundary = (tight.startOutgoing + tight.blendBeats * period - a.origin!!) / (32 * period)
        assertEquals(Math.rint(boundary), boundary, 1e-6)
        // A track that ends too soon leaves no room.
        assertNull(MixScheduler.schedule(0, 1, decision, a, b, rate, rate, 48_000, pos, pos + 12.0 * rate, 0.0))
        // The soft blend sits against the end of the outgoing track.
        val soft = MixScheduler.schedule(0, 1, MixDecision(MixKind.SOFT, 1.0, 0.5, null, null), TrackSummary(null, null), TrackSummary(null, null), rate, rate, 48_000, pos, 130.0 * rate, 0.0)!!
        assertEquals(130.0 * rate - MixScheduler.TAIL_SECONDS * rate, soft.startOutgoing + 8.0 * rate, 1.0)
    }

    @Test fun withoutAnOriginAnyDownbeatIsABoundary() {
        val rate = 44_100
        val grid = BeatAnalysis(120.0, 0.5 * rate, 10.0 * rate, 2, 0.9f)
        val boundary = MixScheduler.nextBoundary(grid, null, 50.0 * rate)
        val j = Math.round((boundary - grid.anchorSample) / grid.periodSamples)
        assertEquals(0L, Math.floorMod(j + 2, 4L))
        assertTrue(boundary >= 50.0 * rate)
    }

    @Test fun theMixerHandsTheTrackOverAndEndsOnTheIncomingDeck() {
        val rate = 48_000
        val mixer = DjMixer(rate)
        val a = PcmRing(60 * rate, rate); val b = PcmRing(60 * rate, rate)
        // Deck A is a 100 Hz tone, deck B a 3 kHz tone. Both are written in full, so that the mixer never starves.
        val tone = { hz: Double -> FloatArray(2 * 40 * rate) { i -> (0.4 * sin(2 * PI * hz * (i / 2) / rate)).toFloat() } }
        a.write(tone(100.0), 40 * rate); b.write(tone(3000.0), 40 * rate)
        mixer.setSource(0, RingSource { a }); mixer.setSource(1, RingSource { b })
        val plan = MixPlan(0, 1, 5.0 * rate, 2.0 * rate, 4 * rate, 1.0, MixKind.BEAT, 2, 2, 0.5)
        mixer.setPlan(plan)
        val out = FloatArray(2 * 4800)
        var rendered = 0
        var peak = 0f
        while (rendered < 12 * rate) {
            mixer.render(out, 4800)
            for (v in out) peak = maxOf(peak, abs(v))
            rendered += 4800
        }
        assertEquals(1, mixer.finishedSeq)
        assertEquals(1, mixer.primary)
        assertTrue(!mixer.mixing)
        assertTrue("the output stays inside full scale: $peak", peak <= 1.0f)
        // A plays 5 s, then B enters at 2 s for the 4 s of the mix and plays on alone for the 3 s that remain.
        assertEquals(9.0 * rate, mixer.position(1), rate * 0.1)
    }

    @Test fun aBadNextTrackIsReplacedByTheBestMatchInTheQueue() {
        val rate = 44_100
        fun grid(bpm: Double) = BeatAnalysis(bpm, 60.0 / bpm * rate, 1000.0, 0, 0.9f)
        val out = TrackSummary(grid(124.0), "8A", 7.0)
        val clash = Candidate(1, TrackSummary(grid(90.0), "3B", 2.0), rate)       // far in tempo, key and energy
        val good = Candidate(2, TrackSummary(grid(126.0), "9A", 7.5), rate)       // close in all three
        val fine = Candidate(3, TrackSummary(grid(122.0), "8A", 6.0), rate)
        assertTrue("the clash is below 70 percent", MixPlanner.decide(out, clash.summary, rate, rate).match < MixPlanner.MIN_MATCH)
        val picked = MixPlanner.choose(out, rate, listOf(clash, good, fine), nextIndex = 1)!!
        assertTrue("a bad next track gives way to a good one, not to itself", picked.index != 1)
        assertTrue("the choice reaches 70 percent", MixPlanner.decide(out, picked.summary, rate, rate).match >= MixPlanner.MIN_MATCH)
        val best = listOf(good, fine).maxByOrNull { MixPlanner.decide(out, it.summary, rate, rate).match }!!
        assertEquals("it is the best of the candidates", best.index, picked.index)
        assertEquals("a good next track stays", 2, MixPlanner.choose(out, rate, listOf(good, fine), nextIndex = 2)!!.index)
        // When nothing reaches 70 percent the best one still wins, if it is clearly better than the next track.
        val weakA = Candidate(1, TrackSummary(grid(90.0), "3B", 2.0), rate)
        val weakB = Candidate(2, TrackSummary(grid(100.0), "5B", 3.5), rate)
        val pick = MixPlanner.choose(out, rate, listOf(weakA, weakB), nextIndex = 1)!!
        assertTrue(MixPlanner.decide(out, pick.summary, rate, rate).match >= MixPlanner.decide(out, weakA.summary, rate, rate).match)
        assertNull(MixPlanner.choose(out, rate, emptyList(), nextIndex = 1))
        assertNull("an unknown next track is not replaced", MixPlanner.choose(out, rate, listOf(good, fine), nextIndex = 1))
    }

    @Test fun aPlainCrossfadeKeepsTheLoudnessConstant() {
        val rate = 48_000
        val mixer = DjMixer(rate)
        val a = PcmRing(60 * rate, rate); val b = PcmRing(60 * rate, rate)
        // Two tones at different pitches, so that their powers add: a plain equal-power crossfade must keep the sum steady.
        val tone = { hz: Double -> FloatArray(2 * 30 * rate) { i -> (0.5 * sin(2 * PI * hz * (i / 2) / rate)).toFloat() } }
        a.write(tone(220.0), 30 * rate); b.write(tone(330.0), 30 * rate)
        mixer.setSource(0, RingSource { a }); mixer.setSource(1, RingSource { b })
        mixer.setPlan(MixPlan(0, 1, 4.0 * rate, 0.0, 8 * rate, 1.0, MixKind.CROSSFADE, 0, 0, 0.5))
        val out = FloatArray(2 * 4800)
        val rms = ArrayList<Double>()
        var rendered = 0
        while (rendered < 16 * rate) {
            mixer.render(out, 4800)
            // The windows that lie inside the crossfade, which runs from 4 s to 12 s.
            if (rendered in (5 * rate)..(11 * rate)) rms.add(Math.sqrt(out.indices.step(2).sumOf { (out[it] * out[it]).toDouble() } / 4800))
            rendered += 4800
        }
        assertEquals(1, mixer.finishedSeq)
        val spread = rms.max() - rms.min()
        assertTrue("loudness moves by $spread during the crossfade", spread < 0.06)
    }
}
