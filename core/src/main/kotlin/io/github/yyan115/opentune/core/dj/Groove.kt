package io.github.yyan115.opentune.core.dj

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The groove of a song: how far into the beat its off-beat eighth notes fall. In a straight groove they fall at 0.5, half way. In a swung or shuffled one,
 * as in disco, funk or neo-soul, they fall later, up to about 0.67 for a triplet feel. Two songs at the same tempo can still clash if one is straight and the
 * other swung, so the mix looks at this too.
 *
 * The off-beats are read from the hats and shakers, the band above 5 kHz, folded onto one beat of the grid (which sits on the kick).
 */
object Swing {
    private const val BINS = 48
    private const val MAX_BEATS = 48
    private const val MIN_BEATS = 16
    /** The window in which the off-beat eighth is looked for, as a position in the beat. Sixteenths at 0.25 and 0.75 are left out. */
    private const val FROM = 0.40
    private const val TO = 0.70
    /** The off-beats must hold at least this share of the onsets of the high band, or the song has no hats to read. */
    private const val MIN_SHARE = 0.12

    /** The position of the off-beat eighth in the beat, from 0.4 to 0.7, or null when the song has no clear off-beat in the high band. */
    fun measure(ring: PcmRing, beat: BeatAnalysis, fromFrame: Double): Double? {
        val rate = ring.rate
        val period = beat.periodSamples
        val end = (ring.written - 1).toDouble()
        var start = max(fromFrame, (ring.oldest() + rate / 4).toDouble())
        if (end - start > MAX_BEATS * period) start = end - MAX_BEATS * period
        if (end - start < MIN_BEATS * period) return null

        val high1 = Biquad(true, rate, 5_000.0); val high2 = Biquad(true, rate, 5_000.0)
        val smooth = 1.0 - exp(-1.0 / (0.001 * rate))
        val lag = max(2, (0.002 * rate).toInt())
        val history = DoubleArray(lag)
        var at = 0
        var level = 0.0
        val histogram = DoubleArray(BINS)
        val skip = (0.1 * rate).toLong()
        var f = start.toLong()
        val last = end.toLong()
        while (f < last) {
            val x = ((ring.left(f) + ring.right(f)) * 0.5f).toDouble()
            level += (abs(high2.process(high1.process(x))) - level) * smooth
            val before = history[at]; history[at] = level; at = (at + 1) % lag
            val rise = level - before
            if (rise > 0.0 && f - start > skip) {
                val turns = (f - beat.anchorSample) / period
                histogram[min(BINS - 1, ((turns - floor(turns)) * BINS).toInt())] += rise
            }
            f++
        }
        val total = histogram.sum()
        if (total <= 1e-5) return null
        val from = (FROM * BINS).toInt(); val to = (TO * BINS).toInt()
        var peak = from; var mass = 0.0
        for (bin in from..to) { mass += histogram[bin]; if (histogram[bin] > histogram[peak]) peak = bin }
        if (mass / total < MIN_SHARE) return null
        // The centre of mass around the peak, over five bins, in beats.
        var weight = 0.0; var moment = 0.0
        for (k in -2..2) {
            val bin = peak + k
            if (bin < from || bin > to) continue
            weight += histogram[bin]; moment += histogram[bin] * (bin + 0.5)
        }
        return if (weight <= 0.0) null else moment / weight / BINS
    }

    /** How alike two grooves are, from 1 for the same to 0 for 0.12 or more apart. */
    fun score(a: Double?, b: Double?): Double? = if (a == null || b == null) null else (1.0 - abs(a - b) / 0.12).coerceIn(0.0, 1.0)
}
