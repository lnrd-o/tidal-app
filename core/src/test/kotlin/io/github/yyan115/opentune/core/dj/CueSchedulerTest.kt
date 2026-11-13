package io.github.yyan115.opentune.core.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CueSchedulerTest {
    private val rate = 44_100
    private val period = 0.5 * rate                       // 120 bpm
    private val decision = MixDecision(MixKind.BEAT, 1.0, 1.0, 120.0, 120.0, 6.0, 6.0, 1.0, 1.0, 32)
    private val pos = 100.0 * rate

    private fun outgoing(cues: List<CueMark> = emptyList()) =
        TrackSummary(BeatAnalysis(120.0, period, 90.0 * rate + 0.1 * rate, 0, 0.9f), "8A", 6.0, origin = 10.0 * rate, cues = cues)

    private fun incoming(cues: List<CueMark> = emptyList()) =
        TrackSummary(BeatAnalysis(120.0, period, 3.0 * rate, 0, 0.9f), "8A", 6.0, origin = 3.0 * rate, cues = cues)

    private fun schedule(a: TrackSummary, b: TrackSummary, end: Double = 400.0 * rate, roll: Boolean = false, written: Double = 60.0 * rate) =
        MixScheduler.schedule(0, 1, decision, a, b, rate, rate, 48_000, pos, end, 0.0, roll, written)

    @Test fun withoutCuesThePlanIsAsBefore() {
        val plan = schedule(outgoing(), incoming())!!
        assertEquals(3.0 * rate, plan.startIncoming, 1e-6)
        assertNull(plan.roll)
        assertFalse(plan.dropEntry)
    }

    @Test fun theBlendStartsAtTheOutroOfTheOutgoingSong() {
        val plain = schedule(outgoing(), incoming())!!
        val outroAt = 220.0 * rate
        val anchored = schedule(outgoing(listOf(CueMark(CueKind.OUTRO, outroAt))), incoming())!!
        println("blend at ${plain.startOutgoing / rate} s without the outro, ${anchored.startOutgoing / rate} s with it at ${outroAt / rate} s")
        assertTrue("the blend starts at the outro, not before it: ${anchored.startOutgoing}", anchored.startOutgoing >= outroAt - period - 1.0)
        assertTrue("and soon after it", anchored.startOutgoing < outroAt + 40 * period)
        assertTrue(plain.startOutgoing < anchored.startOutgoing)
        // It still lands on a block of 32 beats from the origin.
        val boundary = (anchored.startOutgoing + anchored.blendBeats * period - 10.0 * rate) / (32 * period)
        assertEquals(Math.rint(boundary), boundary, 1e-6)
    }

    @Test fun anOutroThatCannotFitIsLeftOut() {
        // The outro is so near the end that the blend and its tail do not fit: the plan falls back to the first moment that fits.
        val end = 330.0 * rate
        val plan = schedule(outgoing(listOf(CueMark(CueKind.OUTRO, end - 2.0 * rate))), incoming(), end = end)
        assertNotNull(plan)
        assertTrue(plan!!.startOutgoing + plan.lengthOut.toDouble() * rate / 48_000 <= end)
    }

    @Test fun theIncomingSongEntersSoThatItsDropFallsOnTheSwap() {
        val drop = 40.0 * rate
        val plan = schedule(outgoing(), incoming(listOf(CueMark(CueKind.DROP, drop))))!!
        assertTrue(plan.dropEntry)
        assertEquals("the swap is the blend after the entry", drop, plan.startIncoming + plan.blendBeats * period, 1e-6)
    }

    @Test fun aDropThatIsTooEarlyOrNotDecodedYetIsNotUsed() {
        // The entry would come before the first frame that the ring still holds.
        val early = schedule(outgoing(), incoming(listOf(CueMark(CueKind.DROP, 4.0 * rate))))!!
        assertFalse(early.dropEntry); assertEquals(3.0 * rate, early.startIncoming, 1e-6)
        // The entry is further on than the song will have been decoded when the mix starts.
        val far = schedule(outgoing(), incoming(listOf(CueMark(CueKind.DROP, 500.0 * rate))), written = 10.0 * rate)!!
        assertFalse(far.dropEntry)
    }

    @Test fun theRollEndsOnTheSwapAndIsOptional() {
        val plan = schedule(outgoing(), incoming(), roll = true)!!
        val roll = plan.roll
        assertNotNull(roll)
        assertEquals(plan.startOutgoing + plan.blendBeats * period, roll!!.endFrame, 1e-6)
        assertEquals(period, roll.beatFrames, 1e-9)
        assertTrue("the roll begins inside the blend", roll.startFrame > plan.startOutgoing)
        assertNull(schedule(outgoing(), incoming(), roll = false)!!.roll)
    }

    @Test fun aSoftMixIsNeverRolledOrCued() {
        val soft = MixScheduler.schedule(0, 1, MixDecision(MixKind.SOFT, 1.0, 0.5, null, null), TrackSummary(null, null), TrackSummary(null, null),
            rate, rate, 48_000, pos, 130.0 * rate, 0.0, true, 60.0 * rate)!!
        assertNull(soft.roll); assertFalse(soft.dropEntry)
    }
}
