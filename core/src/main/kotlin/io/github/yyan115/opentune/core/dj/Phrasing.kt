package io.github.yyan115.opentune.core.dj

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Where the first phrase of a track begins. Dance, pop and hip-hop are built from blocks of 32 beats (8 bars), and the first drum hit
 * of a track starts the first block. Every later phrase begins a whole number of 32 beats after it.
 */
object PhraseOrigin {
    /**
     * [head] is the onset envelope from the start of the analysis, [headFirstSample] the ring frame of its first value.
     * Returns the ring frame of the beat of [beat]'s grid that carries the first strong onset, or null when there is none.
     */
    fun detect(head: FloatArray, headFirstSample: Double, beat: BeatAnalysis, rate: Int): Double? {
        val fps = rate.toDouble() / OnsetEnvelope.HOP
        val limit = min(head.size, (fps * 24).toInt())
        if (limit < fps * 4) return null
        val sorted = head.copyOfRange(0, limit).sortedArray()
        val level = sorted[(limit * 0.95).toInt().coerceIn(0, limit - 1)]
        if (level <= 0f) return null
        val threshold = level * 0.4f
        for (f in 2 until limit - 2) {
            val v = head[f]
            if (v < threshold) continue
            if (v < head[f - 1] || v < head[f + 1] || v < head[f - 2] || v < head[f + 2]) continue
            val sample = headFirstSample + f.toDouble() * OnsetEnvelope.HOP
            val j = ((sample - beat.anchorSample) / beat.periodSamples).roundToLong()
            val snapped = beat.anchorSample + j * beat.periodSamples
            // An onset that is far from the grid is not a beat of the groove, so it does not start the phrase.
            if (abs(sample - snapped) > 0.3 * beat.periodSamples) continue
            return snapped
        }
        return null
    }
}

/** How much a stretch of a track is going on, from 1 (a quiet pad) to 10 (a loud, dense drop). */
object Energy {
    private const val FLUX_REFERENCE = 12.0

    /** The loudness of the frames from [from] to [to] of a ring, in decibels relative to full scale, sampling every fourth frame. */
    fun rmsDb(ring: PcmRing, from: Long, to: Long): Double {
        val start = max(from, ring.oldest()); val end = min(to, ring.written)
        if (end - start < ring.rate / 4) return -90.0
        var sum = 0.0; var count = 0
        var f = start
        while (f < end) { val m = (ring.left(f) + ring.right(f)) * 0.5; sum += m * m; count++; f += 4 }
        val rms = sqrt(sum / count)
        return if (rms <= 1e-6) -90.0 else 20.0 * log10(rms)
    }

    /** Combines loudness and the density of onsets: loud and busy is high, quiet and sparse is low. */
    fun score(rmsDb: Double, meanFlux: Double): Double {
        val loud = ((rmsDb + 30.0) / 22.0).coerceIn(0.0, 1.0)
        val rhythm = (meanFlux / FLUX_REFERENCE).coerceIn(0.0, 1.0)
        return 1.0 + 9.0 * (0.6 * loud + 0.4 * rhythm)
    }
}

/**
 * Finds where the bass drums sit against the beat grid. The grid comes from the onset envelope, which sees all of the music below 4 kHz,
 * so its beats can lie a few milliseconds from the kick. Measuring the kick itself, in the low band, lets two tracks be put kick on kick.
 */
object KickAligner {
    private const val WARM_SECONDS = 0.06

    /**
     * The median distance in seconds from the beats of the grid to the start of the kick that follows them, over [beats] beats from [fromFrame].
     * A positive value means that the kick comes after the grid. Returns null when the ring does not hold enough of the music.
     */
    fun offsetSeconds(ring: PcmRing, anchor: Double, period: Double, fromFrame: Double, beats: Int = 8): Double? {
        val rate = ring.rate
        val half = (period * 0.35).toInt()
        val warm = (WARM_SECONDS * rate).toInt()
        val results = ArrayList<Double>()
        var n = ceil((fromFrame - anchor) / period).toLong()
        val first = Biquad(false, rate, 140.0); val second = Biquad(false, rate, 140.0)
        val smooth = 1.0 - Math.exp(-1.0 / (0.002 * rate))
        for (i in 0 until beats) {
            val center = anchor + n * period; n++
            val start = (center - half - warm).toLong(); val end = (center + half).toLong()
            if (start < ring.oldest() || end >= ring.written) continue
            first.reset(); second.reset()
            val length = (end - start).toInt()
            val envelope = DoubleArray(length)
            var level = 0.0
            for (k in 0 until length) {
                val x = (ring.left(start + k) + ring.right(start + k)) * 0.5
                level += (abs(second.process(first.process(x.toDouble()))) - level) * smooth
                envelope[k] = level
            }
            var peak = 0.0
            for (k in warm until length) peak = max(peak, envelope[k])
            if (peak <= 1e-4) continue
            val threshold = peak * 0.5
            var k = warm
            while (k < length && envelope[k] < threshold) k++
            if (k >= length) continue
            // Sub-sample position of the crossing.
            val before = envelope[k - 1]; val after = envelope[k]
            val fraction = if (after > before) (threshold - before) / (after - before) else 0.0
            results += (start + k - 1 + fraction - center) / rate
        }
        if (results.size < 3) return null
        results.sort()
        return results[results.size / 2]
    }
}
