package io.github.yyan115.opentune.core.dj

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The echo-out wash, the swing of a groove and the arc of a set. */
class SetShapeTest {
    private val rate = 48_000

    // ---- the echo ----

    @Test fun theEchoRepeatsAfterItsDelayBouncesToTheOtherSideAndDies() {
        val echo = EchoTail(rate)
        echo.configure(12_000, 0.58f)
        val left = FloatArray(12_000 * 9); val right = FloatArray(12_000 * 9)
        for (i in left.indices) { echo.process(if (i == 0) 1f else 0f, if (i == 0) 1f else 0f); left[i] = echo.outLeft; right[i] = echo.outRight }
        fun energy(a: FloatArray, centre: Int) = (centre - 400..centre + 400).sumOf { abs(a[it]).toDouble() }
        assertTrue("the first repeat is on the left: ${energy(left, 12_000)}", energy(left, 12_000) > 0.9 && energy(right, 12_000) < 1e-6)
        assertTrue("the second is on the right, darker and weaker", energy(right, 24_000) in 0.2..1.0 && energy(left, 24_000) < 1e-6)
        assertTrue("it dies away: ${energy(left, 12_000 * 8) + energy(right, 12_000 * 8)}", energy(left, 12_000 * 8) + energy(right, 12_000 * 8) < 0.05 * energy(left, 12_000))
        assertTrue(left.all { it.isFinite() } && right.all { it.isFinite() })
    }

    private fun tone(hz: Double, seconds: Int, amplitude: Double = 0.4) =
        FloatArray(2 * seconds * rate) { i -> (amplitude * sin(2 * PI * hz * (i / 2) / rate)).toFloat() }

    private fun goertzel(samples: FloatArray, from: Int, to: Int, hz: Double): Double {
        var re = 0.0; var im = 0.0
        for (n in from until to) { val x = samples[n].toDouble(); val w = 2 * PI * hz * n / rate; re += x * cos(w); im += x * sin(w) }
        return sqrt(re * re + im * im) / (to - from)
    }

    private fun runSoft(echo: EchoPlan?): FloatArray {
        val mixer = DjMixer(rate)
        val a = PcmRing(60 * rate, rate); val b = PcmRing(60 * rate, rate)
        val toneA = tone(200.0, 40); val toneB = tone(110.0, 40)
        a.write(toneA, 40 * rate); b.write(toneB, 40 * rate)
        mixer.setSource(0, RingSource { a }); mixer.setSource(1, RingSource { b })
        mixer.setPlan(MixPlan(0, 1, 240_000.0, 96_000.0, 8 * rate, 1.0, MixKind.SOFT, 8, 8, 0.5, echo = echo))
        val out = FloatArray(20 * rate)
        val block = FloatArray(2 * 480)
        var done = 0
        while (done < out.size) { mixer.render(block, 480); for (i in 0 until 480) out[done + i] = block[i * 2]; done += 480 }
        return out
    }

    @Test fun theOutgoingSongRingsOutInTheEchoAfterItHasLeft() {
        val dry = runSoft(null)
        val wet = runSoft(EchoPlan(rate * 3 / 8))
        // The soft transition starts at 5 s and lasts 8 s. From 13.5 to 16 s the 200 Hz of the outgoing song is gone, except in the echo.
        val from = (13.5 * rate).toInt(); val to = 16 * rate
        val without = goertzel(dry, from, to, 200.0); val with = goertzel(wet, from, to, 200.0)
        println("200 Hz after the transition: without the echo $without, with it $with")
        assertTrue("the echo carries it: $with vs $without", with > 0.008 && with > 8 * without)
        // And a few seconds later the echo is gone.
        assertTrue("it dies: ${goertzel(wet, 18 * rate, 20 * rate, 200.0)}", goertzel(wet, 18 * rate, 20 * rate, 200.0) < 0.2 * with)
        var biggest = 0f
        for (i in 1 until wet.size) biggest = maxOf(biggest, abs(wet[i] - wet[i - 1]))
        assertTrue("a step of $biggest", biggest < 0.06f)
    }

    @Test fun aSoftPlanGetsAnEchoOnlyWhenAsked() {
        val grid = BeatAnalysis(120.0, 0.5 * rate, 0.0, 0, 0.9f)
        val soft = MixDecision(MixKind.SOFT, 1.0, 0.5, null, null)
        val a = TrackSummary(grid, "8A", 6.0)
        val with = MixScheduler.schedule(0, 1, soft, a, TrackSummary(null, null), rate, rate, rate, 100.0 * rate, 400.0 * rate, 0.0, false, 1e12, true)!!
        assertNotNull(with.echo)
        assertEquals("three quarters of a beat", 0.75 * 0.5 * rate, with.echo!!.delayFrames.toDouble(), 2.0)
        assertNull(MixScheduler.schedule(0, 1, soft, a, TrackSummary(null, null), rate, rate, rate, 100.0 * rate, 400.0 * rate, 0.0)!!.echo)
    }

    // ---- the groove ----

    /** A groove at 120 bpm: a kick on every beat, and hats at [positions] of each beat. */
    private fun groove(positions: List<Double>): PcmRing {
        val period = 0.5 * rate
        val out = FloatArray((32 * rate))
        var t = 0.3 * rate
        while (t < out.size) {
            val start = t.toInt()
            for (i in 0 until (0.12 * rate).toInt()) if (start + i < out.size) out[start + i] += 0.8f * exp(-i / (0.035f * rate)) * sin(2 * PI * 55.0 * i / rate).toFloat()
            for (p in positions) {
                val hat = (t + p * period).toInt()
                for (i in 0 until 500) if (hat + i < out.size) out[hat + i] += 0.2f * (if (i % 2 == 0) 1f else -1f) * exp(-i / 100f)
            }
            t += period
        }
        val ring = PcmRing(out.size + 8192, rate)
        ring.write(FloatArray(out.size * 2) { out[it / 2] }, out.size)
        return ring
    }

    private val grid = BeatAnalysis(120.0, 0.5 * rate, 0.3 * rate, 0, 0.9f)

    @Test fun aStraightGrooveReadsHalfWayAndASwungOneLater() {
        val straight = Swing.measure(groove(listOf(0.5)), grid, 1.0 * rate)
        val swung = Swing.measure(groove(listOf(0.62)), grid, 1.0 * rate)
        val sixteenths = Swing.measure(groove(listOf(0.25, 0.5, 0.75)), grid, 1.0 * rate)
        println("straight $straight, swung $swung, sixteenths $sixteenths")
        assertNotNull(straight); assertNotNull(swung); assertNotNull(sixteenths)
        assertEquals(0.50, straight!!, 0.03)
        assertEquals(0.62, swung!!, 0.04)
        assertEquals("sixteenth hats still read the eighth", 0.50, sixteenths!!, 0.04)
    }

    @Test fun aSongWithoutHatsHasNoGrooveToRead() {
        assertNull(Swing.measure(groove(emptyList()), grid, 1.0 * rate))
    }

    private fun summary(swing: Double?, energy: Double = 6.0, bpm: Double = 120.0) =
        TrackSummary(BeatAnalysis(bpm, 60.0 / bpm * rate, 0.0, 0, 0.9f), "8A", energy, swing = swing)

    @Test fun grooveClashChangesTheMix() {
        fun decide(a: Double?, b: Double?) = MixPlanner.decide(summary(a), summary(b), rate, rate)
        val same = decide(0.50, 0.51)
        assertEquals(MixKind.BEAT, same.kind); assertEquals(32, same.blendBeats)
        val near = decide(0.50, 0.58)
        assertEquals("a small difference gets the short blend", MixKind.BEAT, near.kind); assertEquals(16, near.blendBeats)
        val clash = decide(0.50, 0.63)
        assertEquals("a straight groove and a swung one are not locked on the beat", MixKind.SOFT, clash.kind)
        // Without both grooves nothing changes.
        assertEquals(MixKind.BEAT, decide(null, 0.63).kind); assertEquals(32, decide(0.5, null).blendBeats)
        assertTrue("the groove counts in the match", same.match > clash.match)
        assertNull(decide(null, 0.6).grooveScore)
        assertEquals(0.4 * 1.0 + 0.3 * 1.0 + 0.3 * 1.0, decide(null, null).match, 1e-9)
    }

    // ---- the arc of a set ----

    @Test fun aRunOfLoudSongsPenalisesAnotherAndRewardsABreather() {
        val peaks = EnergyArc(listOf(8.0, 8.0, 8.0, 9.0))
        assertTrue("prolonging the plateau: ${peaks.score(9.0)}", peaks.score(9.0) < MixPlanner.PLATEAU_SCORE)
        assertTrue("a breather: ${peaks.score(5.0)}", peaks.score(5.0) >= MixPlanner.BREAK_SCORE)
        val lows = EnergyArc(listOf(2.0, 2.0, 3.0, 2.0))
        assertTrue(lows.score(2.0) < MixPlanner.PLATEAU_SCORE)
        assertTrue("a lift: ${lows.score(6.0)}", lows.score(6.0) >= MixPlanner.BREAK_SCORE)
        // Nothing is known of a short history or of a song without energy.
        assertEquals(0.5, EnergyArc(listOf(8.0)).score(9.0), 0.0)
        assertEquals(0.5, peaks.score(null), 0.0)
        // A varied history has no plateau.
        assertTrue(EnergyArc(listOf(5.0, 7.0, 4.0, 6.0)).score(8.0) in 0.6..1.0)
    }

    @Test fun theChoiceBreaksAPlateauOnlyWithAnotherSongThatGoesWell() {
        val outgoing = summary(null, energy = 8.0)
        val next = Candidate(1, summary(null, energy = 9.0), rate)
        val breather = Candidate(2, summary(null, energy = 5.5), rate)
        val badMatch = Candidate(3, summary(null, energy = 5.0, bpm = 90.0), rate)
        val plateau = EnergyArc(listOf(8.0, 8.0, 8.0, 8.0))
        assertEquals("without an arc the next song stays", 1, MixPlanner.choose(outgoing, rate, listOf(next, breather), 1)!!.index)
        assertEquals("a plateau gives way to a breather that goes well", 2, MixPlanner.choose(outgoing, rate, listOf(next, breather), 1, plateau)!!.index)
        assertEquals("but not to a song that does not go well", 1, MixPlanner.choose(outgoing, rate, listOf(next, badMatch), 1, plateau)!!.index)
        assertEquals("and nothing changes without a plateau", 1, MixPlanner.choose(outgoing, rate, listOf(next, breather), 1, EnergyArc(listOf(5.0, 7.0, 4.0, 6.0)))!!.index)
    }

    @Test fun aSongThatIsOnlyGuessedToGoWellDoesNotHoldThePlaceOfOneThatIsKnownTo() {
        val outgoing = summary(null, energy = 6.0)
        // The next song is known only from a catalogue: its tempo and key look perfect, and its energy is not known.
        val guessed = Candidate(1, TrackSummary(BeatAnalysis(120.0, 0.5 * rate, 0.0, 0, 0.9f), "8A", null), rate, verified = false)
        val known = Candidate(2, summary(null, energy = 6.5), rate)
        assertEquals("the known song takes the place", 2, MixPlanner.choose(outgoing, rate, listOf(guessed, known), 1)!!.index)
        // A guess that is the only one stays, as there is nothing better.
        assertEquals(1, MixPlanner.choose(outgoing, rate, listOf(guessed), 1)!!.index)
        // A guess is lowered by the penalty, and a known song is not.
        val g = MixPlanner.decide(outgoing, guessed.summary, rate, rate).match
        assertTrue("$g", g - MixPlanner.GUESS_PENALTY < MixPlanner.decide(outgoing, known.summary, rate, rate).match)
    }

    @Test fun aMixThatDoesNotFitOnTheBeatKeepsTheMatchOfThePair() {
        val d = MixPlanner.decide(summary(null, energy = 6.0), summary(null, energy = 6.5), rate, rate)
        assertEquals(MixKind.BEAT, d.kind)
        val soft = MixDecision(MixKind.SOFT, 1.0, d.keyScore, d.bpmOutgoing, d.bpmIncoming, d.energyOutgoing, d.energyIncoming, d.energyScore, d.tempoScore, 16, d.grooveScore)
        assertEquals("a fallback to soft does not lower the match", d.match, soft.match, 1e-9)
    }
}

