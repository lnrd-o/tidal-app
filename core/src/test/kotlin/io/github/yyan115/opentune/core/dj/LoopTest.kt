package io.github.yyan115.opentune.core.dj

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopTest {
    private val rate = 48_000

    private fun ramp(seconds: Int): FloatArray {
        // A slow ramp, so that any repeat or jump shows in the values: 0.9 over 10 seconds, then flat.
        val frames = seconds * rate
        return FloatArray(frames * 2) { i -> (0.9f * minOf(1f, (i / 2) / (10f * rate))) }
    }

    private fun tone(hz: Double, seconds: Int, amplitude: Double = 0.4) =
        FloatArray(2 * seconds * rate) { i -> (amplitude * sin(2 * PI * hz * (i / 2) / rate)).toFloat() }

    private fun mixerWith(a: FloatArray, b: FloatArray? = null): Triple<DjMixer, PcmRing, PcmRing> {
        val mixer = DjMixer(rate)
        val ringA = PcmRing(60 * rate, rate); val ringB = PcmRing(60 * rate, rate)
        ringA.write(a, a.size / 2)
        if (b != null) ringB.write(b, b.size / 2)
        mixer.setSource(0, RingSource { ringA }); mixer.setSource(1, RingSource { ringB })
        return Triple(mixer, ringA, ringB)
    }

    private fun render(mixer: DjMixer, frames: Int, into: FloatArray? = null, at: Int = 0): Int {
        val block = FloatArray(2 * 480)
        var done = 0
        while (done < frames) {
            val n = minOf(480, frames - done)
            mixer.render(block, n)
            if (into != null) for (i in 0 until n) into[at + done + i] = block[i * 2]
            done += n
        }
        return done
    }

    @Test fun aLoopRepeatsItsFramesAndTheDeckGoesWhereItWouldBeWhenItEnds() {
        val (mixer, _, _) = mixerWith(ramp(40))
        render(mixer, 2 * rate)
        val start = mixer.position(0)
        val length = 4800.0
        mixer.setLoop(0, start, length)
        val out = FloatArray(rate)
        render(mixer, rate, out)
        // The loop is a tenth of a second of a ramp that rises 0.009 in that time: the output cannot leave that range.
        val low = 0.9f * (start / (10.0 * rate)).toFloat() - 0.003f
        val high = 0.9f * ((start + length) / (10.0 * rate)).toFloat() + 0.003f
        val inside = out.drop(1000)
        assertTrue("the output leaves the loop: ${inside.min()} .. ${inside.max()} instead of $low .. $high", inside.min() >= low && inside.max() <= high)
        // It repeats: a sample and the one a loop later are the same, away from the fades.
        for (k in listOf(2000, 3000, 4000, 20_000, 30_000)) assertEquals("sample $k", out[k], out[k + 4800], 0.0015f)

        mixer.endLoop(0, true)
        render(mixer, 480)
        val shadow = start + rate.toDouble() + 480
        val after = mixer.position(0)
        println("after the loop the deck is at $after, the timeline at $shadow")
        assertEquals(shadow, after, 1000.0)
        // The ramp goes on rising where the timeline is, not where the loop was.
        val onward = FloatArray(4800)
        render(mixer, 4800, onward)
        assertTrue(onward.last() > high + 0.04f)
    }

    @Test fun aLoopWithoutShadowContinuesFromWhereItIs() {
        val (mixer, _, _) = mixerWith(ramp(40))
        render(mixer, rate)
        val start = mixer.position(0)
        mixer.setLoop(0, start, 2400.0)
        render(mixer, 4800)
        mixer.endLoop(0, false)
        render(mixer, 480)
        // Without the jump the deck goes on from inside the loop, close to where it began.
        assertEquals(start + 480.0, mixer.position(0), 3000.0)
    }

    private fun rollPlan(): MixPlan {
        val roll = RollPlan(endFrame = 336_000.0, beatFrames = 24_000.0)
        return MixPlan(0, 1, 240_000.0, 96_000.0, 192_000, 1.0, MixKind.BEAT, 4, 4, 0.5, roll)
    }

    @Test fun theRollRepeatsTheLastTwoBeatsBeforeTheSwapAndComesBackInTime() {
        val (mixer, _, _) = mixerWith(ramp(40), tone(110.0, 40))
        mixer.setPlan(rollPlan())
        val positions = HashMap<Int, Double>()
        var frames = 0
        val checks = listOf(300_000, 307_200, 319_200, 330_000, 336_480, 345_000)
        for (target in checks) { frames += render(mixer, target - frames); positions[target] = mixer.position(0) }
        println("deck position at each time: $positions")
        // Stage one repeats the first half beat of the roll, from 288000 to 300000.
        assertTrue("stage 1: ${positions[307_200]}", positions[307_200]!! in 287_900.0..300_500.0)
        // Stage two repeats a quarter beat from 312000, stage three an eighth from 324000.
        assertTrue("stage 2: ${positions[319_200]}", positions[319_200]!! in 311_900.0..318_500.0)
        assertTrue("stage 3: ${positions[330_000]}", positions[330_000]!! in 323_900.0..327_500.0)
        // Before the roll and after it the deck is on its time.
        assertEquals(300_000.0, positions[300_000]!!, 1000.0)
        assertEquals(345_000.0, positions[345_000]!!, 1000.0)
    }

    @Test fun theRollLeavesNoStepInTheSound() {
        val (mixer, _, _) = mixerWith(tone(90.0, 40), tone(110.0, 40))
        mixer.setPlan(rollPlan())
        val out = FloatArray(14 * rate)
        render(mixer, out.size, out)
        var biggest = 0f; var at = 0
        for (i in 1 until out.size) { val d = abs(out[i] - out[i - 1]); if (d > biggest) { biggest = d; at = i } }
        println("the biggest step between neighbouring samples: $biggest at $at")
        // A 110 Hz tone of 0.4 moves 0.006 per sample. A cut would show as 0.4 or more.
        assertTrue("a step of $biggest at $at", biggest < 0.03f)
    }

    @Test fun aMixWithoutARollIsUnchanged() {
        val (mixer, _, _) = mixerWith(tone(90.0, 40), tone(110.0, 40))
        mixer.setPlan(MixPlan(0, 1, 240_000.0, 96_000.0, 192_000, 1.0, MixKind.BEAT, 4, 4, 0.5))
        render(mixer, 14 * rate)
        assertEquals(1, mixer.finishedSeq)
        assertEquals(1, mixer.primary)
    }

    @Test fun aHeldMixerIsSilentAndStaysWhereItIsUntilItIsReleased() {
        val (mixer, _, _) = mixerWith(tone(220.0, 40))
        render(mixer, rate)
        mixer.setHold(true)
        render(mixer, 2400)                       // the fade to silence
        val held = mixer.position(0)
        val out = FloatArray(rate)
        render(mixer, rate, out)
        assertTrue("silent while held: ${out.maxOf { abs(it) }}", out.maxOf { abs(it) } < 1e-6f)
        assertEquals("the deck does not move while held", held, mixer.position(0), 1.0)
        mixer.setHold(false)
        val after = FloatArray(rate)
        render(mixer, rate, after)
        assertTrue("it plays again", after.maxOf { abs(it) } > 0.1f)
        assertTrue("from where it stopped: ${mixer.position(0)} after $held", mixer.position(0) in held..(held + 1.2 * rate))
    }
}
