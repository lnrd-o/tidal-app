package io.github.yyan115.opentune.core.dj

import io.github.yyan115.opentune.core.Spectrum
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The onset strength of a signal: for each hop of [HOP] samples, how much the spectrum below 4 kHz grew since the last hop
 * (the positive part of the log-magnitude spectral flux). Kicks, snares and plucks show as peaks.
 */
class OnsetEnvelope(val rate: Int) {
    private val half = Spectrum.SIZE / 2
    private val window = FloatArray(Spectrum.SIZE)
    private val re = FloatArray(half)
    private val im = FloatArray(half)
    private val power = FloatArray(half)
    private val previous = FloatArray(half)
    private val hop = FloatArray(HOP)
    private val maxBin = minOf(half - 1, (4000.0 * Spectrum.SIZE / rate).toInt())
    private var hopFill = 0
    private var consumed = 0L
    private var values = FloatArray(8192)
    /** The onset strength of the kick alone: the same flux, over the bins below about 130 Hz. */
    private var lowValues = FloatArray(8192)
    private val lowMax = max(3, (130.0 * Spectrum.SIZE / rate).toInt())

    /** The ring frame of the first sample that was fed. */
    var startSample = 0L
        private set
    /** The number of frames kept, and the number that were dropped from the front to bound the memory. */
    var count = 0
        private set
    var dropped = 0
        private set

    /** Called with the power spectrum of each frame, for other analyses that share the transform. */
    var onFrame: ((FloatArray) -> Unit)? = null

    /** The onset strength of the frame that [onFrame] is handed now. */
    var lastFlux = 0f
        private set

    fun reset(start: Long) {
        startSample = start; consumed = 0; hopFill = 0; count = 0; dropped = 0
        window.fill(0f); previous.fill(0f)
    }

    /** The ring frame at which the onset of frame [k] (counted from the start, including dropped ones) is placed. */
    fun timeOf(k: Int): Double = startSample + k.toDouble() * HOP + Spectrum.SIZE - ONSET_LEAD

    fun feed(mono: FloatArray, n: Int) {
        for (i in 0 until n) {
            hop[hopFill++] = mono[i]
            if (hopFill == HOP) { hopFill = 0; slide(); consumed += HOP; if (consumed >= Spectrum.SIZE) frame() }
        }
    }

    private fun slide() {
        System.arraycopy(window, HOP, window, 0, Spectrum.SIZE - HOP)
        System.arraycopy(hop, 0, window, Spectrum.SIZE - HOP, HOP)
    }

    private fun frame() {
        Spectrum.powerSpectrum(window, power, re, im)
        var flux = 0f
        var lowFlux = 0f
        for (bin in 2..maxBin) {
            val magnitude = sqrt(power[bin]) * (4f / Spectrum.SIZE)
            val v = ln(1f + 800f * magnitude)
            val d = v - previous[bin]
            if (d > 0f) { flux += d; if (bin <= lowMax) lowFlux += d }
            previous[bin] = v
        }
        if (count == values.size) {
            if (count >= MAX_FRAMES) {
                val cut = count / 2
                System.arraycopy(values, cut, values, 0, count - cut); System.arraycopy(lowValues, cut, lowValues, 0, count - cut)
                count -= cut; dropped += cut
            } else { values = values.copyOf(values.size * 2); lowValues = lowValues.copyOf(lowValues.size * 2) }
        }
        lowValues[count] = lowFlux
        values[count++] = flux
        lastFlux = flux
        onFrame?.invoke(power)
    }

    /** The first values since the start, or null when the front was already dropped. */
    fun head(maxFrames: Int): FloatArray? = if (dropped > 0) null else values.copyOfRange(0, min(count, maxFrames))

    /** The mean onset strength between two ring frames, or 0 when no frame lies in the range. */
    fun meanFlux(fromSample: Double, toSample: Double): Double {
        val from = ((fromSample - startSample - Spectrum.SIZE + ONSET_LEAD) / HOP).toInt().coerceAtLeast(dropped)
        val to = ((toSample - startSample - Spectrum.SIZE + ONSET_LEAD) / HOP).toInt().coerceAtMost(dropped + count - 1)
        if (to <= from) return 0.0
        var sum = 0.0
        for (k in from..to) sum += values[k - dropped]
        return sum / (to - from + 1)
    }

    /** The newest [maxFrames] values of the kick alone, from the same frame as the values of [tail]. */
    fun lowTail(maxFrames: Int): FloatArray = lowValues.copyOfRange(max(0, count - maxFrames), count)

    /** The newest [maxFrames] values, and the absolute index of the first of them. */
    fun tail(maxFrames: Int): Pair<FloatArray, Int> {
        val from = max(0, count - maxFrames)
        return values.copyOfRange(from, count) to (dropped + from)
    }

    companion object {
        const val HOP = 256
        /** The flux of a frame peaks when an onset has just entered its window, about this many samples before the window ends. */
        const val ONSET_LEAD = 400.0
        const val MAX_FRAMES = 172 * 45
    }
}

/** The tempo and the grid of beats of a piece. Beats fall at [anchorSample] plus whole multiples of [periodSamples], in ring frames. */
class BeatAnalysis(
    val bpm: Double, val periodSamples: Double, val anchorSample: Double, val beatsSinceDownbeat: Int, val confidence: Float,
    /** Three or four. The bar is counted in this many beats. */
    val beatsPerBar: Int = 4,
    /** True when the drums told which beat starts the bar (see [BarFinder]); false when it is only a guess. */
    val barKnown: Boolean = false,
) {
    /** The first downbeat at or after [sample], as a ring frame. */
    fun nextDownbeat(sample: Double): Double {
        var j = floor((sample - anchorSample) / periodSamples).toLong() - 1
        while (true) {
            val at = anchorSample + j * periodSamples
            if (at >= sample && Math.floorMod(j + beatsSinceDownbeat, beatsPerBar.toLong()) == 0L) return at
            j++
        }
    }
}

/** Finds the tempo and the beat grid in an onset envelope with a comb filter: the grid that collects the most onset energy wins. */
object BeatTracker {
    /** The kick must mark a new tempo with at least this much contrast, and with this many times the contrast of the old one, for the tempo to change. */
    private const val KICK_MIN = 0.4
    private const val KICK_GAIN = 1.3

    fun analyze(envelope: FloatArray, firstSample: Double, rate: Int, minBpm: Double = 70.0, maxBpm: Double = 180.0, kick: FloatArray? = null): BeatAnalysis? {
        val n = envelope.size
        val fps = rate.toDouble() / OnsetEnvelope.HOP
        if (n < fps * 8) return null
        val e = prepare(envelope, fps)

        // Coarse: every tempo in half a beat per minute steps, each with its best phase, weighted toward common tempos.
        var bestBpm = 0.0; var bestScore = -1.0
        var bpm = minBpm
        while (bpm <= maxBpm) {
            val period = 60.0 * fps / bpm
            val prior = exp(-0.5 * square(ln(bpm / 125.0) / 0.35))
            val score = bestPhase(e, period, 1.0).second * prior
            if (score > bestScore) { bestScore = score; bestBpm = bpm }
            bpm += 0.5
        }

        // The beats of a song are where its kick falls. The hats and the snares divide a beat, and they can make the tempo look double (or half), so when the
        // kick is known its pattern decides between a tempo and its double and half.
        if (kick != null && kick.size == envelope.size) bestBpm = followTheKick(prepare(kick, fps), bestBpm, fps, minBpm, maxBpm)

        // Fine: within 1.5 percent of the coarse tempo in steps of 0.02 percent, with a finer phase.
        var bpmFine = bestBpm; var phase = 0.0; var peak = -1.0
        val low = bestBpm * 0.985; val high = bestBpm * 1.015; val step = bestBpm * 0.0002
        var candidate = low
        while (candidate <= high) {
            val (p, s) = bestPhase(e, 60.0 * fps / candidate, 0.5)
            if (s > peak) { peak = s; phase = p; bpmFine = candidate }
            candidate += step
        }

        val period = 60.0 * fps / bpmFine
        // Confidence: how far the best grid stands above the average grid of the same tempo.
        var sum = 0.0; var count = 0
        var f = 0.0
        while (f < period) { sum += comb(e, f, period); count++; f += 1.0 }
        val mean = sum / count
        val confidence = ((peak - mean) / (peak + 1e-9)).coerceIn(0.0, 1.0).toFloat()

        // The last beat of the window is the anchor, and the downbeat is the beat of a bar that collects the most onset energy.
        val lastK = floor((n - 2 - phase) / period).toInt()
        if (lastK < 8) return null
        val bar = DoubleArray(4)
        for (k in 0..lastK) bar[k % 4] += at(e, phase + k * period)
        val downbeatK = bar.indices.maxByOrNull { bar[it] }!!
        val sinceDownbeat = Math.floorMod(lastK - downbeatK, 4)
        val anchorFrame = phase + lastK * period
        return BeatAnalysis(bpmFine, period * OnsetEnvelope.HOP, firstSample + anchorFrame * OnsetEnvelope.HOP, sinceDownbeat, confidence)
    }

    private fun square(x: Double) = x * x

    /**
     * How much more the kick falls on the beats of a grid of [bpm] than half way between them, in the units of an envelope with a mean of one.
     * At the right tempo the kick sits on the beats and nothing is half way between them. At double the tempo, half of the beats are empty, and at half the
     * tempo the beats that the grid skips have kicks half way between its own.
     */
    private fun kickContrast(kick: FloatArray, bpm: Double, fps: Double): Double {
        val period = 60.0 * fps / bpm
        val (phase, on) = bestPhase(kick, period, 1.0)
        return on - comb(kick, phase + period / 2, period)
    }

    /** The tempo, or its double, half or three to two, that the kick of the song marks the best. The one that is found stays unless another is clearly better. */
    private fun followTheKick(kick: FloatArray, bpm: Double, fps: Double, minBpm: Double, maxBpm: Double): Double {
        var best = bpm
        var bestContrast = kickContrast(kick, bpm, fps)
        // Half and double are the usual mistakes, and three to two (dotted notes and triplets against the beat) the next.
        for (factor in doubleArrayOf(0.5, 2.0 / 3.0, 0.75, 4.0 / 3.0, 1.5, 2.0)) {
            val candidate = bpm * factor
            if (candidate < minBpm || candidate > maxBpm) continue
            val contrast = kickContrast(kick, candidate, fps)
            // The kick must be clear at the new tempo, and clearly clearer than at the one that was found.
            if (contrast >= KICK_MIN && contrast > bestContrast * KICK_GAIN) { best = candidate; bestContrast = contrast }
        }
        return best
    }

    /** Removes the slow average, so that only the peaks remain, and scales to a mean of 1. */
    private fun prepare(env: FloatArray, fps: Double): FloatArray {
        val n = env.size
        val w = max(3, fps.toInt())
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + env[i]
        val out = FloatArray(n)
        var total = 0.0
        for (i in 0 until n) {
            val a = max(0, i - w / 2); val b = min(n, i + w / 2 + 1)
            val local = (prefix[b] - prefix[a]) / (b - a)
            val v = max(0.0, env[i] - local * 1.0)
            out[i] = v.toFloat(); total += v
        }
        val mean = total / n
        if (mean > 0) for (i in 0 until n) out[i] = (out[i] / mean).toFloat()
        return out
    }

    private fun at(e: FloatArray, x: Double): Double {
        val i = x.toInt()
        if (x < 0 || i >= e.size - 1) return 0.0
        val f = x - i
        return e[i] * (1 - f) + e[i + 1] * f
    }

    /** The mean onset energy at the beats of a grid with the given phase and period, in frames. */
    private fun comb(e: FloatArray, phase: Double, period: Double): Double {
        var sum = 0.0; var count = 0
        var x = phase
        while (x < e.size - 1) { sum += at(e, x); x += period; count++ }
        return if (count == 0) 0.0 else sum / count
    }

    private fun bestPhase(e: FloatArray, period: Double, step: Double): Pair<Double, Double> {
        var best = -1.0; var bestPhase = 0.0
        var p = 0.0
        while (p < period) {
            val s = comb(e, p, period)
            if (s > best) { best = s; bestPhase = p }
            p += step
        }
        return bestPhase to best
    }
}
