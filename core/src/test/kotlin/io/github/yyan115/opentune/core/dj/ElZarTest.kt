package io.github.yyan115.opentune.core.dj

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElZarTest {
    private val rate = 48_000

    private fun tone(hz: Double, amplitude: Double, seconds: Double): FloatArray {
        val frames = (seconds * rate).toInt()
        return FloatArray(frames * 2) { i -> (amplitude * sin(2 * PI * hz * (i / 2) / rate)).toFloat() }
    }

    private fun rmsDb(buffer: FloatArray, fromFrame: Int = 0): Double {
        var sum = 0.0; var count = 0
        var i = fromFrame * 2
        while (i < buffer.size) { sum += buffer[i] * buffer[i]; count++; i += 2 }
        val rms = sqrt(sum / count)
        return if (rms < 1e-9) -180.0 else 20 * log10(rms)
    }

    private fun run(elzar: ElZar, input: FloatArray): FloatArray {
        val out = input.copyOf()
        var frame = 0
        val total = out.size / 2
        while (frame < total) {
            val n = minOf(480, total - frame)
            elzar.processBlock(out, frame, n)
            frame += n
        }
        return out
    }

    @Test fun withEverythingOffNothingIsTouched() {
        val input = tone(440.0, 0.5, 1.0)
        val elzar = ElZar(rate)
        assertTrue(!elzar.active)
        assertTrue(input.contentEquals(run(elzar, input)))
        elzar.on = true
        assertTrue("on with no amounts is still off", input.contentEquals(run(elzar, input)))
        elzar.glue = 0.6f; elzar.on = false
        assertTrue("an amount without the switch is still off", input.contentEquals(run(elzar, input)))
    }

    @Test fun theGlueTurnsLoudPassagesDownAndKeepsQuietOnesAlone() {
        val loud = tone(1000.0, 0.9, 2.0)
        val quiet = tone(1000.0, 0.02, 2.0)
        val elzar = ElZar(rate); elzar.on = true; elzar.glue = 1f
        val outLoud = run(elzar, loud)
        val reduction = elzar.reductionDb
        println("glue reduction on a loud tone: $reduction dB")
        assertTrue("gain reduction $reduction dB", reduction > 3f)
        // The compressor narrows the distance between loud and quiet passages.
        val quietEl = ElZar(rate); quietEl.on = true; quietEl.glue = 1f
        val outQuiet = run(quietEl, quiet)
        val before = rmsDb(loud, rate) - rmsDb(quiet, rate)
        val after = rmsDb(outLoud, rate) - rmsDb(outQuiet, rate)
        println("loud-to-quiet distance: $before dB before, $after dB after")
        assertTrue("distance $before -> $after", after < before - 1.5)
        assertTrue("quiet passages stay quiet: ${quietEl.reductionDb}", quietEl.reductionDb < 0.5f)
    }

    @Test fun theGlueNeverClipsAndStaysFinite() {
        val elzar = ElZar(rate); elzar.on = true; elzar.glue = 1f; elzar.tape = 1f; elzar.vinyl = 1f
        val out = run(elzar, tone(220.0, 0.95, 3.0))
        assertTrue(out.all { it.isFinite() })
        assertTrue("peak ${out.maxOf { abs(it) }}", out.maxOf { abs(it) } < 1.6f)
    }

    @Test fun tapeSoftensTheTopEndAndKeepsTheBody() {
        fun level(hz: Double, tape: Float): Double {
            val elzar = ElZar(rate); elzar.on = true; elzar.tape = tape
            // A small signal, so that saturation does not matter and only the roll-off does.
            val out = run(elzar, tone(hz, 0.05, 1.0))
            return rmsDb(out, rate / 2) - rmsDb(tone(hz, 0.05, 1.0), rate / 2)
        }
        val body = level(1000.0, 1f)
        val top = level(16_000.0, 1f)
        println("tape at full: 1 kHz $body dB, 16 kHz $top dB")
        assertTrue("the body stays: $body", abs(body) < 1.5)
        assertTrue("the top end falls: $top", top < body - 2.0)
        assertTrue("a light tape changes little: ${level(16_000.0, 0.1f)}", level(16_000.0, 0.1f) > top)
    }

    /** The level in decibels of one frequency in the left channel from [fromFrame], by the Goertzel method. */
    private fun component(buffer: FloatArray, hz: Double, fromFrame: Int): Double {
        val k = 2 * PI * hz / rate
        val coefficient = 2 * Math.cos(k)
        var s1 = 0.0; var s2 = 0.0
        var count = 0
        var i = fromFrame * 2
        while (i < buffer.size) { val s0 = buffer[i] + coefficient * s1 - s2; s2 = s1; s1 = s0; count++; i += 2 }
        val power = s1 * s1 + s2 * s2 - coefficient * s1 * s2
        return 10 * log10(max(power, 1e-18) / (count.toDouble() * count / 4))
    }

    @Test fun tapeSaturationAddsHarmonicsToALoudTone() {
        val input = tone(1000.0, 0.8, 1.0)
        fun thirdHarmonic(tape: Float, glue: Float): Double {
            val elzar = ElZar(rate); elzar.on = true; elzar.tape = tape; elzar.glue = glue
            val out = run(elzar, input)
            return component(out, 3000.0, rate / 2) - component(out, 1000.0, rate / 2)
        }
        val saturated = thirdHarmonic(1f, 0f)
        val clean = thirdHarmonic(0.0001f, 0f)
        println("third harmonic: ${"%.1f".format(saturated)} dB with tape, ${"%.1f".format(clean)} dB with almost none")
        assertTrue("saturation adds a third harmonic: $saturated", saturated > -40.0)
        assertTrue("and a light tape adds much less: $clean", clean < saturated - 10.0)
    }

    @Test fun vinylMakesQuietCrackleAndSurfaceNoiseFromSilence() {
        val elzar = ElZar(rate); elzar.on = true; elzar.vinyl = 1f
        val out = run(elzar, FloatArray(2 * rate * 6))
        val floor = rmsDb(out)
        val peak = 20 * log10(out.maxOf { abs(it) }.toDouble())
        println("vinyl at full: noise floor $floor dBFS, loudest pop $peak dBFS")
        assertTrue("noise floor $floor", floor in -80.0..-50.0)
        assertTrue("there are pops above the floor: $peak", peak > floor + 12)
        assertTrue("the pops stay far below the music: $peak", peak < -15.0)
        assertTrue(out.all { it.isFinite() })
    }

    @Test fun vinylAtAHalfIsQuieterThanAtFull() {
        fun floor(amount: Float): Double {
            val elzar = ElZar(rate); elzar.on = true; elzar.vinyl = amount
            return rmsDb(run(elzar, FloatArray(2 * rate * 4)))
        }
        assertTrue(floor(0.5f) < floor(1f))
        assertTrue(floor(0.1f) < floor(0.5f))
    }

    @Test fun theMixerAppliesElZarToWhatIsHeardAndNotToSilence() {
        val mixer = DjMixer(rate)
        val a = PcmRing(20 * rate, rate)
        a.write(FloatArray(2 * 4 * rate) { i -> (0.4 * sin(2 * PI * 330.0 * (i / 2) / rate)).toFloat() }, 4 * rate)
        mixer.setSource(0, RingSource { a })
        mixer.setElZar(true, 0f, 0f, 1f)
        val out = FloatArray(2 * 4800)
        var rendered = 0; var noisy = 0.0
        val plain = DjMixer(rate); plain.setSource(0, RingSource { a })
        val reference = FloatArray(2 * 4800)
        while (rendered < 3 * rate) {
            mixer.render(out, 4800); plain.render(reference, 4800)
            for (k in out.indices) noisy = max(noisy, abs((out[k] - reference[k]).toDouble()))
            rendered += 4800
        }
        assertTrue("Tone Lab changed the signal: $noisy", noisy > 1e-4)
        // After the music ends, the ring is dry: the mixer outputs silence and Tone Lab adds nothing.
        val silence = FloatArray(2 * 4800)
        repeat(20) { mixer.render(silence, 4800) }
        assertEquals(0.0, silence.maxOf { abs(it).toDouble() }, 0.0)
    }

    // ---- flavors ----

    private fun glued(flavor: Flavor, amount: Float = 1f): ElZar = ElZar(rate).also { it.on = true; it.glue = amount; it.flavor = flavor }

    @Test fun everyFlavorCompressesALoudToneAndStaysFinite() {
        for (flavor in Flavor.entries) {
            val elzar = glued(flavor)
            val out = run(elzar, tone(1000.0, 0.9, 3.0))
            println("$flavor: reduction ${"%.1f".format(elzar.reductionDb)} dB, peak ${"%.2f".format(out.maxOf { abs(it) })}")
            assertTrue("$flavor reduces: ${elzar.reductionDb}", elzar.reductionDb > 1.5f)
            assertTrue("$flavor is finite", out.all { it.isFinite() })
            assertTrue("$flavor stays bounded: ${out.maxOf { abs(it) }}", out.maxOf { abs(it) } < 2.0f)
        }
    }

    @Test fun everyFlavorLeavesAQuietSignalAlone() {
        for (flavor in Flavor.entries) {
            if (flavor == Flavor.PARALLEL) continue // it works on quiet signals on purpose, to bring out detail
            val elzar = glued(flavor, 0.5f)
            run(elzar, tone(1000.0, 0.01, 2.0))
            assertTrue("$flavor works on a quiet tone: ${elzar.reductionDb}", elzar.reductionDb < 0.6f)
        }
    }

    @Test fun theFlavorsLetGoAtTheirOwnPace() {
        // A loud burst, then silence: the share of the reduction that is still there 120 ms later tells how fast the circuit lets go.
        fun shareLeft(flavor: Flavor): Double {
            val elzar = glued(flavor)
            run(elzar, tone(1000.0, 0.9, 1.5))
            val worked = elzar.reductionDb
            run(elzar, FloatArray(2 * (0.12 * rate).toInt()))
            return (elzar.reductionDb / worked).toDouble()
        }
        val fet = shareLeft(Flavor.FET); val opto = shareLeft(Flavor.OPTO); val mu = shareLeft(Flavor.VARI_MU); val vca = shareLeft(Flavor.VCA)
        println("share of the reduction left after 120 ms: FET $fet, VCA $vca, Opto $opto, Vari-Mu $mu")
        assertTrue("the FET lets go faster than the opto: $fet vs $opto", fet < opto)
        assertTrue("and faster than the vari-mu: $fet vs $mu", fet < mu)
    }

    @Test fun theFetAttacksFasterThanTheVariMu() {
        // A short loud burst: the fast circuit has turned it down by then, the slow one has hardly started.
        fun reductionAfter(flavor: Flavor): Float {
            val elzar = glued(flavor)
            run(elzar, tone(1000.0, 0.9, 0.02))
            return elzar.reductionDb
        }
        val fet = reductionAfter(Flavor.FET); val mu = reductionAfter(Flavor.VARI_MU)
        println("reduction after 20 ms: FET $fet dB, Vari-Mu $mu dB")
        assertTrue("$fet vs $mu", fet > mu + 1f)
    }

    @Test fun theVariMuKneeStiffensWithTheLevel() {
        val s = GlueSettings.of(Flavor.VARI_MU, 1f)
        val elzar = glued(Flavor.VARI_MU)
        // The ratio grows with the level: the extra reduction for the next 6 dB is bigger high above the threshold than just over it.
        val near = elzar.reductionFor(s.threshold + 6, s) - elzar.reductionFor(s.threshold, s)
        val far = elzar.reductionFor(s.threshold + 18, s) - elzar.reductionFor(s.threshold + 12, s)
        println("vari-mu: next 6 dB adds $near dB near the threshold, $far dB far above it")
        assertTrue("$near vs $far", far > near * 2)
    }

    @Test fun theFetAddsMoreGritThanTheVcaWhenItWorksHard() {
        fun thirdHarmonic(flavor: Flavor): Double {
            val elzar = glued(flavor)
            val out = run(elzar, tone(1000.0, 0.8, 2.0))
            return component(out, 3000.0, rate) - component(out, 1000.0, rate)
        }
        val fet = thirdHarmonic(Flavor.FET); val vca = thirdHarmonic(Flavor.VCA)
        println("third harmonic: FET $fet dB, VCA $vca dB")
        assertTrue("$fet vs $vca", fet > vca + 6)
    }

    @Test fun theParallelFlavorBlendsAndKeepsTheOriginalOnTop() {
        val elzar = glued(Flavor.PARALLEL, 1f)
        val input = tone(1000.0, 0.5, 2.0)
        val out = run(elzar, input)
        // The dry signal is still most of the sound: the output stays close to the input in level.
        val difference = rmsDb(out, rate) - rmsDb(input, rate)
        println("parallel: level change ${"%.1f".format(difference)} dB")
        assertTrue("$difference", abs(difference) < 6.0)
    }

    @Test fun theParallelFlavorLiftsQuietDetail() {
        val elzar = glued(Flavor.PARALLEL, 1f)
        val quiet = tone(1000.0, 0.01, 2.0)
        val out = run(elzar, quiet)
        val lift = rmsDb(out, rate) - rmsDb(quiet, rate)
        println("parallel lifts a quiet tone by ${"%.1f".format(lift)} dB")
        assertTrue("$lift", lift > 3.0)
    }

    private fun with(block: ElZar.() -> Unit): ElZar = ElZar(rate).apply { on = true; block() }

    private fun peak(buffer: FloatArray): Float = buffer.maxOf { abs(it) }

    @Test fun theBypassMatchesTheLoudnessOfTheProcessedSound() {
        val input = tone(800.0, 0.5, 4.0)
        val processed = run(with { glue = 1f; tape = 0.8f; console = 1f; clip = 1f }, input)
        val bypassed = run(with { glue = 1f; tape = 0.8f; console = 1f; clip = 1f; bypass = true }, input)
        val from = 3 * rate
        val difference = abs(rmsDb(processed, from) - rmsDb(bypassed, from))
        println("loudness difference between the processed sound and the bypass: $difference dB")
        assertTrue("$difference dB", difference < 1.0)
        // The bypass is the original, only with a steady gain: the shape of the wave is kept.
        val ratio1 = bypassed[2 * from + 200] / input[2 * from + 200]
        val ratio2 = bypassed[2 * from + 4000] / input[2 * from + 4000]
        assertTrue("$ratio1 vs $ratio2", abs(ratio1 - ratio2) < 0.02f * abs(ratio1))
    }

    @Test fun everyTapeTypeStaysFiniteAndTheCassetteIsThinnerAndHissier() {
        val quiet = FloatArray(rate * 2 * 2)
        val hiss = TapeType.entries.associateWith { type -> rmsDb(run(with { tape = 1f; tapeType = type }, quiet), rate / 2) }
        println("hiss by tape: $hiss")
        for (type in TapeType.entries) assertTrue("$type", run(with { tape = 1f; tapeType = type }, tone(440.0, 0.5, 1.0)).all { it.isFinite() })
        assertTrue("the cassette hisses more than the studio tape", hiss[TapeType.CASSETTE]!! > hiss[TapeType.STUDIO]!! + 5.0)
        val lows = tone(40.0, 0.3, 1.0)
        val cassette = rmsDb(run(with { tape = 0.5f; tapeType = TapeType.CASSETTE }, lows), rate / 2)
        val studio = rmsDb(run(with { tape = 0.5f; tapeType = TapeType.STUDIO }, lows), rate / 2)
        assertTrue("the cassette loses the low end: $cassette vs $studio", cassette < studio - 3.0)
    }

    @Test fun theWornTapeLosesMoreTopThanTheStudioTape() {
        val highs = tone(12_000.0, 0.3, 1.0)
        val worn = rmsDb(run(with { tape = 1f; tapeType = TapeType.WORN }, highs), rate / 2)
        val studio = rmsDb(run(with { tape = 1f; tapeType = TapeType.STUDIO }, highs), rate / 2)
        assertTrue("$worn vs $studio", worn < studio - 3.0)
    }

    @Test fun aDustyRecordCracklesMoreThanANewOne() {
        fun pops(condition: VinylCondition): Int {
            val out = run(with { vinyl = 1f; vinylCondition = condition }, FloatArray(rate * 2 * 8))
            var count = 0; var quiet = 0
            for (i in out.indices step 2) { if (abs(out[i]) > 0.003f) { if (quiet > 600) count++; quiet = 0 } else quiet++ }
            return count
        }
        val new = pops(VinylCondition.NEW); val dusty = pops(VinylCondition.DUSTY)
        println("pops in 8 s: new $new, dusty $dusty")
        assertTrue("$new vs $dusty", dusty > new)
    }

    @Test fun theScratchComesRoundAtEveryTurnOfTheRecord() {
        fun repeats(fast: Boolean): Double {
            val out = run(with { vinyl = 1f; vinylCondition = VinylCondition.WORN; vinylFast = fast }, FloatArray(rate * 2 * 8))
            val turn = (rate * (if (fast) 60.0 / 45.0 else 60.0 / 33.333)).toInt()
            // The scratch starts at the same place in every turn: the loudest of the first 40 samples after it is well above the surface noise.
            return (1 until 5).map { k ->
                var best = 0f
                for (i in 0 until 40) best = max(best, abs(out[2 * (k * turn + i + 1)]))
                best.toDouble()
            }.average()
        }
        assertTrue("${repeats(false)}", repeats(false) > 0.002); assertTrue("${repeats(true)}", repeats(true) > 0.002)
    }

    @Test fun theConsoleSaturatesTheLowsMoreThanTheHighs() {
        fun gainChange(hz: Double): Double {
            val input = tone(hz, 0.7, 1.0)
            return rmsDb(run(with { console = 1f }, input), rate / 2) - rmsDb(input, rate / 2)
        }
        val low = gainChange(60.0); val high = gainChange(5_000.0)
        println("console: lows $low dB, highs $high dB")
        assertTrue("$low vs $high", low < high - 0.8)
    }

    @Test fun theTubeAddsEvenHarmonics() {
        val input = tone(500.0, 0.6, 1.0)
        val out = run(with { tube = 1f }, input)
        fun level(hz: Double): Double {
            var re = 0.0; var im = 0.0
            for (n in rate / 2 until rate) { val x = out[2 * n]; val w = 2 * PI * hz * n / rate; re += x * kotlin.math.cos(w); im += x * sin(w) }
            return 20 * log10(sqrt(re * re + im * im) / (rate / 2) + 1e-12)
        }
        val second = level(1000.0); val fundamental = level(500.0)
        println("tube second harmonic ${second - fundamental} dB below the tone")
        assertTrue("${second - fundamental}", second - fundamental > -45.0)
    }

    @Test fun theAirLiftsTheTopAndLeavesTheLowsAlone() {
        fun change(hz: Double): Double { val i = tone(hz, 0.1, 1.0); return rmsDb(run(with { air = 1f }, i), rate / 2) - rmsDb(i, rate / 2) }
        val high = change(10_000.0); val low = change(200.0)
        println("air: highs $high dB, lows $low dB")
        assertTrue("$high", high > 2.0); assertTrue("$low", abs(low) < 0.5)
    }

    @Test fun theClipRoundsOffThePeaksAndLeavesQuietPassagesAlone() {
        val loud = run(with { clip = 1f }, tone(300.0, 1.4, 1.0))
        assertTrue("the peak stays under the ceiling: ${peak(loud)}", peak(loud) <= 0.9f)
        val quiet = tone(300.0, 0.2, 1.0)
        val out = run(with { clip = 1f }, quiet)
        // A quiet tone gets only the gain of the stage, 6 dB at the full setting, and no distortion.
        assertEquals(rmsDb(quiet, 0) + 6.02, rmsDb(out, 0), 0.3)
    }

    @Test fun midSideGluePressesTheSidesApartFromTheCentre() {
        val frames = rate * 2
        val input = FloatArray(frames * 2)
        for (n in 0 until frames) {
            val mid = 0.9 * sin(2 * PI * 1000.0 * n / rate); val side = 0.05 * sin(2 * PI * 3000.0 * n / rate)
            input[2 * n] = (mid + side).toFloat(); input[2 * n + 1] = (mid - side).toFloat()
        }
        fun sideLevel(midSide: Boolean): Double {
            val out = run(with { glue = 1f; this.midSide = midSide }, input)
            var sum = 0.0
            for (n in rate until frames) { val s = (out[2 * n] - out[2 * n + 1]) * 0.5; sum += s * s }
            return 10 * log10(sum / (frames - rate) + 1e-18)
        }
        val linked = sideLevel(false); val split = sideLevel(true)
        println("side level: linked $linked dB, mid/side $split dB")
        assertTrue("$split vs $linked", split > linked + 2.0)
    }

    @Test fun everyNewStageTogetherStaysFinite() {
        val elzar = with { glue = 1f; tape = 1f; vinyl = 1f; console = 1f; tube = 1f; air = 1f; clip = 1f; midSide = true; tapeType = TapeType.WORN; flavor = Flavor.FET }
        val out = run(elzar, tone(220.0, 1.0, 3.0))
        assertTrue(out.all { it.isFinite() })
        assertTrue("${peak(out)}", peak(out) < 1.5f)
    }
}
