package io.github.yyan115.opentune.core.dj

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Puts the beat grid of a track where a DJ would put it, by listening to the drums.
 *
 * The grid from [BeatTracker] comes from all of the music below 4 kHz. It can lie on the hats instead of the kick, and its bar can start on a
 * snare. For mixing, the kick must meet the kick and bar one must meet bar one, so this looks at two bands:
 *
 * - The **kick** (below 140 Hz) tells where the beat really is: the onsets of the low band are folded onto one beat, and the grid is moved to
 *   the peak of that fold.
 * - The **bar** starts on the beat that has a kick and no snare: for each beat of the bar, the kick strength minus the snare strength (the
 *   band above 1.5 kHz) is averaged over the whole stretch. The beats are counted in threes when the kick comes round every three beats.
 *
 * When the drums do not tell the bar (a kick on every beat and nothing else), the bar is left unknown and the caller keeps its own guess.
 */
object BarFinder {
    private const val BINS = 48
    private const val MAX_BEATS = 64
    private const val MIN_BEATS = 16
    private const val KICK_HZ = 140.0
    private const val SNARE_HZ = 1_500.0
    /** A class of the bar must lead the next by this much to be trusted without a hint. */
    private const val MARGIN = 0.12
    /** Classes within this distance of the best are taken as equally good, and the hint decides among them. */
    private const val TIE = 0.15

    /**
     * Returns [beat] with its anchor moved onto the kick, the beats per bar, and which beat starts the bar ([BeatAnalysis.barKnown]).
     * [fromFrame] is where the music begins in the ring. [hintFrame] is a frame at which the track has a first strong hit: if two beats of the
     * bar are equally good (a kick on one and three), the one that is the same class as the hint wins.
     */
    fun refine(ring: PcmRing, beat: BeatAnalysis, fromFrame: Double, hintFrame: Double? = null): BeatAnalysis {
        val period = beat.periodSamples
        val rate = ring.rate
        var end = (ring.written - 1).toDouble()
        var start = max(fromFrame, (ring.oldest() + rate / 4).toDouble())
        if (end - start > MAX_BEATS * period) start = end - MAX_BEATS * period
        if (end - start < MIN_BEATS * period) return beat

        val anchor = foldKick(ring, beat, start, end)?.let { beat.anchorSample + it } ?: beat.anchorSample

        // One reading of the two bands for each beat that lies wholly in the range.
        val firstBeat = Math.ceil((start + 0.2 * period - anchor) / period).toLong()
        val lastBeat = floor((end - 0.4 * period - anchor) / period).toLong()
        val count = (lastBeat - firstBeat + 1).toInt()
        if (count < MIN_BEATS) return rebuilt(beat, anchor, beat.beatsSinceDownbeat, beat.beatsPerBar, beat.barKnown)
        val kick = DoubleArray(count); val snare = DoubleArray(count)
        val low1 = Biquad(false, rate, KICK_HZ); val low2 = Biquad(false, rate, KICK_HZ)
        val high1 = Biquad(true, rate, SNARE_HZ); val high2 = Biquad(true, rate, SNARE_HZ)
        val smooth = 1.0 - exp(-1.0 / (0.002 * rate))
        val warm = (0.03 * rate).toInt()
        for (n in 0 until count) {
            val center = anchor + (firstBeat + n) * period
            val from = (center - 0.15 * period).toLong(); val to = (center + 0.3 * period).toLong()
            if (from - warm < ring.oldest() || to >= ring.written) continue
            low1.reset(); low2.reset(); high1.reset(); high2.reset()
            var lowLevel = 0.0; var highLevel = 0.0
            var k = 0.0; var s = 0.0
            for (f in from - warm..to) {
                val x = ((ring.left(f) + ring.right(f)) * 0.5f).toDouble()
                lowLevel += (abs(low2.process(low1.process(x))) - lowLevel) * smooth
                highLevel += (abs(high2.process(high1.process(x))) - highLevel) * smooth
                if (f >= from) { if (lowLevel > k) k = lowLevel; if (highLevel > s) s = highLevel }
            }
            kick[n] = k; snare[n] = s
        }
        val medianKick = median(kick); val medianSnare = median(snare)
        if (medianKick <= 1e-6) return rebuilt(beat, anchor, beat.beatsSinceDownbeat, beat.beatsPerBar, beat.barKnown)
        val accentKick = DoubleArray(count) { kick[it] / medianKick }
        val accentSnare = DoubleArray(count) { snare[it] / max(medianSnare, 1e-6) }

        val perBar = beatsPerBar(accentKick)
        val score = DoubleArray(perBar); val seen = IntArray(perBar)
        for (n in 0 until count) {
            val c = Math.floorMod(firstBeat + n, perBar.toLong()).toInt()
            score[c] += accentKick[n] - 0.7 * accentSnare[n]; seen[c]++
        }
        for (c in 0 until perBar) if (seen[c] > 0) score[c] /= seen[c]
        val order = (0 until perBar).sortedByDescending { score[it] }
        val best = order[0]
        val gap = score[best] - score[order[1]]
        val hintClass = hintFrame?.let { Math.floorMod(((it - anchor) / period).roundToInt().toLong(), perBar.toLong()).toInt() }
        val chosen = when {
            hintClass != null && score[hintClass] >= score[best] - TIE -> hintClass
            gap >= MARGIN -> best
            else -> -1
        }
        if (chosen < 0) return rebuilt(beat, anchor, beat.beatsSinceDownbeat, perBar, false)
        return rebuilt(beat, anchor, Math.floorMod(-chosen, perBar), perBar, true)
    }

    /**
     * The first beat of the bar at or before [firstHit], the frame of the first strong drum hit of a track, on the grid [grid].
     * Without a known bar the hit itself, snapped to the grid, starts the phrase.
     */
    fun origin(grid: BeatAnalysis, firstHit: Double): Double {
        val period = grid.periodSamples
        val j = ((firstHit - grid.anchorSample) / period).roundToInt().toLong()
        if (!grid.barKnown) return grid.anchorSample + j * period
        val back = Math.floorMod(j + grid.beatsSinceDownbeat, grid.beatsPerBar.toLong())
        return grid.anchorSample + (j - back) * period
    }

    private fun rebuilt(b: BeatAnalysis, anchor: Double, since: Int, perBar: Int, known: Boolean) =
        BeatAnalysis(b.bpm, b.periodSamples, anchor, since, b.confidence, perBar, known)

    /**
     * Folds the onsets of the kick onto one beat and returns how far the grid has to move to sit on the peak, in frames from -period/2 to period/2,
     * or null when the low band has no clear beat.
     */
    private fun foldKick(ring: PcmRing, beat: BeatAnalysis, start: Double, end: Double): Double? {
        val rate = ring.rate
        val period = beat.periodSamples
        val low1 = Biquad(false, rate, KICK_HZ); val low2 = Biquad(false, rate, KICK_HZ)
        val smooth = 1.0 - exp(-1.0 / (0.003 * rate))
        val lag = max(2, (0.004 * rate).toInt())
        val history = DoubleArray(lag)
        var at = 0
        var level = 0.0
        val histogram = DoubleArray(BINS)
        val skip = (0.1 * rate).toLong()
        var f = start.toLong()
        val last = end.toLong()
        while (f < last) {
            val x = ((ring.left(f) + ring.right(f)) * 0.5f).toDouble()
            level += (abs(low2.process(low1.process(x))) - level) * smooth
            val before = history[at]; history[at] = level; at = (at + 1) % lag
            val rise = level - before
            if (rise > 0.0 && f - start > skip) {
                val turns = (f - beat.anchorSample) / period
                val phase = turns - floor(turns)
                histogram[min(BINS - 1, (phase * BINS).toInt())] += rise
            }
            f++
        }
        val total = histogram.sum()
        if (total <= 1e-4) return null
        // Smooth over the neighbours, find the peak, and take the centre of mass around it.
        val smoothed = DoubleArray(BINS) { histogram[(it + BINS - 1) % BINS] + 2 * histogram[it] + histogram[(it + 1) % BINS] }
        val peak = smoothed.indices.maxByOrNull { smoothed[it] }!!
        var weight = 0.0; var moment = 0.0; var near = 0.0
        for (k in -2..2) {
            val w = histogram[(peak + k + BINS) % BINS]
            weight += w; moment += w * k; near += w
        }
        if (near / total < 0.25 || weight <= 0.0) return null
        val bin = peak + moment / weight + 0.5
        var shift = bin / BINS
        if (shift >= 0.5) shift -= 1.0
        return shift * period
    }

    /** Three when the kick comes round every three beats rather than every four, else four. */
    private fun beatsPerBar(accent: DoubleArray): Int {
        val mean = accent.average()
        val variance = accent.sumOf { (it - mean) * (it - mean) }
        if (variance < 1e-6) return 4
        fun correlation(lag: Int): Double {
            var sum = 0.0
            for (i in 0 until accent.size - lag) sum += (accent[i] - mean) * (accent[i + lag] - mean)
            return sum / variance
        }
        val three = correlation(3); val four = correlation(4)
        return if (three > 0.3 && three > four + 0.15) 3 else 4
    }

    private fun median(values: DoubleArray): Double {
        val sorted = values.sortedArray()
        return sorted[sorted.size / 2]
    }
}
