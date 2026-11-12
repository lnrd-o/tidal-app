package io.github.yyan115.opentune.core.dj

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class MixKind {
    /** The beats of both tracks are matched, the kicks coincide, and the swap lands on a phrase boundary. */
    BEAT,
    /** No usable beat grid, or tracks too far apart in energy: a blend of a few seconds with the same EQ hand-over. */
    SOFT,
    /** A plain equal-power crossfade of a few seconds, for normal playback: no EQ, no filter, nothing analysed. */
    CROSSFADE
}

/**
 * One transition. Positions are frames of the rings of the two decks, which keep the rate of their track.
 * [lengthOut] is counted in frames of the output. [speedIncoming] is the tempo factor applied to the incoming track at the start.
 * The mix has two parts: [blendBeats] beats in which the incoming track grows under the outgoing one, then the swap at [swap]
 * (the fraction of the length at which the bass changes hands), then [tailBeats] beats in which the outgoing track leaves.
 */
data class MixPlan(
    val outgoing: Int,
    val incoming: Int,
    val startOutgoing: Double,
    val startIncoming: Double,
    val lengthOut: Int,
    val speedIncoming: Double,
    val kind: MixKind,
    val blendBeats: Int,
    val tailBeats: Int,
    val swap: Double,
) {
    val beats: Int get() = blendBeats + tailBeats
}

/**
 * The gain of each band of the two tracks over the transition, as a function of its progress from 0 to 1.
 * The incoming track enters with its low band cut and its mids and highs rising. At [swap] comes the bass swap:
 * the outgoing bass is cut and the incoming bass opens within a few milliseconds. The outgoing mids and highs then leave.
 */
object MixCurves {
    private const val SWAP_WIDTH = 0.0015

    /** Equal-power gains for a plain crossfade: the same gain on every band of a track. */
    fun crossfade(u: Double, g: DoubleArray) {
        val p = u.coerceIn(0.0, 1.0)
        val out = cos(PI / 2 * p); val inn = sin(PI / 2 * p)
        g[0] = out; g[1] = out; g[2] = out; g[3] = inn; g[4] = inn; g[5] = inn
    }

    /** Fills [g] with six gains: outgoing low, mid, high, then incoming low, mid, high. */
    fun gains(u: Double, swap: Double, g: DoubleArray) {
        val s = swap.coerceIn(0.2, 0.8)
        val progress = u.coerceIn(0.0, 1.0)
        val inLow = smooth((progress - s) / SWAP_WIDTH + 0.5)
        val rise = if (progress < s) sin(PI / 2 * progress / s) else 1.0
        val riseHigh = if (progress < s) sin(PI / 2 * ((progress - 0.15 * s) / (0.85 * s)).coerceIn(0.0, 1.0)) else 1.0
        val fall = if (progress < s) 0.0 else ((progress - s) / (1 - s)).coerceIn(0.0, 1.0)
        g[0] = 1.0 - inLow
        g[1] = if (progress < s) 1.0 - 0.35 * rise else 0.65 * cos(PI / 2 * fall)
        g[2] = if (progress < s) 1.0 - 0.3 * riseHigh else 0.7 * cos(PI / 2 * (fall / 0.7).coerceIn(0.0, 1.0))
        g[3] = inLow
        g[4] = rise
        g[5] = riseHigh
    }

    private const val LOW_START_HZ = 250.0
    private const val LOW_END_HZ = 18_000.0
    private const val HIGH_START_HZ = 30.0
    private const val HIGH_END_HZ = 3_500.0

    /** How far the incoming low-pass has opened, from 0 (closed at its start) to 1 (open). It opens over the blend, slowly at first. */
    fun lowpassOpen(u: Double, swap: Double): Double {
        val s = swap.coerceIn(0.2, 0.8)
        return if (u >= s) 1.0 else Math.pow((u / s).coerceIn(0.0, 1.0), 1.6)
    }

    /** The cutoff of the low-pass on the incoming track, in hertz. */
    fun lowpassHz(u: Double, swap: Double): Double = LOW_START_HZ * Math.pow(LOW_END_HZ / LOW_START_HZ, lowpassOpen(u, swap))

    /** How far the outgoing high-pass has closed, from 0 (off at the swap) to 1 (a thin sizzle at the end). It speeds up toward the end. */
    fun highpassClosed(u: Double, swap: Double): Double {
        val s = swap.coerceIn(0.2, 0.8)
        return if (u <= s) 0.0 else Math.pow(((u - s) / (1 - s)).coerceIn(0.0, 1.0), 1.4)
    }

    /** The cutoff of the high-pass on the outgoing track, in hertz. */
    fun highpassHz(u: Double, swap: Double): Double = HIGH_START_HZ * Math.pow(HIGH_END_HZ / HIGH_START_HZ, highpassClosed(u, swap))

    /** The tempo factor of the incoming track: its matched value until the swap, then easing back to its own tempo. */
    fun speed(u: Double, speed: Double, swap: Double): Double {
        val s = swap.coerceIn(0.2, 0.8)
        return if (u <= s) speed else speed + (1.0 - speed) * smooth(((u - s) / (1 - s)).coerceIn(0.0, 1.0))
    }

    private fun smooth(x: Double): Double { val t = x.coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }
}

/**
 * What is known about a track for the decision to mix: its tempo grid, where its first phrase begins, its key and its energy.
 * For the outgoing track the energy is that of its end, for the incoming track that of its start.
 */
class TrackSummary(val beat: BeatAnalysis?, val camelot: String?, val energy: Double? = null, val origin: Double? = null)

class MixDecision(
    val kind: MixKind,
    val speedIncoming: Double,
    val keyScore: Double,
    val bpmOutgoing: Double?,
    val bpmIncoming: Double?,
    val energyOutgoing: Double? = null,
    val energyIncoming: Double? = null,
    val energyScore: Double = 0.5,
    val tempoScore: Double = 0.2,
    /** The number of beats before the swap that the incoming track is blended in: 32 when the tracks agree well, else 16. */
    val blendBeats: Int = 16,
) {
    /** How well the two tracks go together, from 0 to 1: tempo 40 percent, key 30 percent, energy 30 percent. */
    val match: Double get() = 0.4 * tempoScore + 0.3 * keyScore + 0.3 * energyScore
}

/** A track further down the queue that could be played next, with what is known about it. */
class Candidate(val index: Int, val summary: TrackSummary, val rate: Int)

object MixPlanner {
    /** A pair of tracks that matches less than this is not mixed if a better one is waiting in the queue. */
    const val MIN_MATCH = 0.70
    /** A match of at least this is almost perfect: tempo, key and energy all agree. */
    const val PERFECT_MATCH = 0.90
    const val MIN_CONFIDENCE = 0.3f
    const val MAX_TEMPO_CHANGE = 0.06
    /** Two tracks further apart than this on the energy scale of 1 to 10 are not matched on the beat. */
    const val MAX_ENERGY_GAP = 4.5
    /** Within this gap the long, 32-beat blend feels natural. */
    const val LONG_BLEND_GAP = 2.5

    /**
     * Picks the track to play next. The track at [nextIndex] stays when it matches [outgoing] by at least [MIN_MATCH]. When it does not,
     * the candidate with the best match takes its place, even if no candidate reaches [MIN_MATCH], as long as it is clearly better.
     * Returns null when there is nothing to choose from.
     */
    fun choose(outgoing: TrackSummary, rateOutgoing: Int, candidates: List<Candidate>, nextIndex: Int): Candidate? {
        val scored = candidates.map { it to decide(outgoing, it.summary, rateOutgoing, it.rate).match }
        // A next track that has not been listened to yet is not replaced: nothing is known that says it is a bad match.
        val next = scored.firstOrNull { it.first.index == nextIndex } ?: return null
        if (next.second >= MIN_MATCH) return next.first
        val best = scored.maxByOrNull { it.second } ?: return null
        return if (best.second <= next.second + 0.02) next.first else best.first
    }

    /** Decides how to mix: matching the beats when both tracks have a clear grid, their tempos are within 6 percent and their energies are close. */
    fun decide(outgoing: TrackSummary, incoming: TrackSummary, rateOutgoing: Int, rateIncoming: Int): MixDecision {
        val a = outgoing.beat; val b = incoming.beat
        val key = Camelot.compatibility(outgoing.camelot, incoming.camelot)
        val eo = outgoing.energy; val ei = incoming.energy
        val gap = if (eo != null && ei != null) abs(eo - ei) else null
        val energyScore = if (gap == null) 0.5 else (1.0 - gap / 6.0).coerceIn(0.0, 1.0)
        if (a != null && b != null && a.confidence >= MIN_CONFIDENCE && b.confidence >= MIN_CONFIDENCE) {
            // The incoming track plays this much faster to bring its beats onto the grid of the outgoing one.
            val speed = (b.periodSamples / rateIncoming) / (a.periodSamples / rateOutgoing)
            val tempoScore = (1.0 - abs(speed - 1.0) / MAX_TEMPO_CHANGE).coerceIn(0.0, 1.0)
            if (abs(speed - 1.0) <= MAX_TEMPO_CHANGE && (gap == null || gap <= MAX_ENERGY_GAP)) {
                val blend = if (gap == null || gap <= LONG_BLEND_GAP) 32 else 16
                return MixDecision(MixKind.BEAT, speed, key, a.bpm, b.bpm, eo, ei, energyScore, tempoScore, blend)
            }
        }
        // No match on the beat. A song at a nearer tempo is still a better neighbour than a far one, so the score is not flat.
        var soft = 0.2
        if (a != null && b != null && a.confidence >= MIN_CONFIDENCE && b.confidence >= MIN_CONFIDENCE) {
            val speed = (b.periodSamples / rateIncoming) / (a.periodSamples / rateOutgoing)
            val nearest = minOf(abs(speed - 1.0), abs(speed / 2 - 1.0), abs(speed * 2 - 1.0))
            soft = 0.2 + 0.3 * (1.0 - nearest / 0.25).coerceIn(0.0, 1.0)
        }
        return MixDecision(MixKind.SOFT, 1.0, key, a?.bpm, b?.bpm, eo, ei, energyScore, soft, 16)
    }
}

/** Places a transition on the grids of the two tracks. */
object MixScheduler {
    const val LEAD_SECONDS = 2.5
    const val TAIL_SECONDS = 1.5
    const val SOFT_SECONDS = 8.0
    const val PHRASE_BEATS = 32
    const val TAIL_BEATS = 16

    /**
     * [posOutgoing] is where the output reads the outgoing ring now, [endOutgoing] is the estimated frame at which the outgoing track ends,
     * and [firstIncoming] is the oldest frame of the incoming ring that can still be read.
     *
     * The swap is placed on a phrase boundary of the outgoing track, 32 beats after the previous one, counted from the first drum hit
     * ([TrackSummary.origin]). The incoming track enters at its own first drum hit [blendBeats] before the swap, so that its first phrase
     * begins with the blend and the swap falls 16 or 32 beats into it. When no phrase origin is known, any downbeat is a boundary.
     * Returns null when the transition does not fit before the outgoing track ends.
     */
    fun schedule(
        outgoing: Int, incoming: Int, decision: MixDecision, a: TrackSummary, b: TrackSummary, rateOutgoing: Int, rateIncoming: Int, outRate: Int,
        posOutgoing: Double, endOutgoing: Double, firstIncoming: Double,
    ): MixPlan? {
        val earliest = posOutgoing + LEAD_SECONDS * rateOutgoing
        val latestEnd = endOutgoing - TAIL_SECONDS * rateOutgoing
        val gridA = a.beat; val gridB = b.beat
        if (decision.kind == MixKind.BEAT && gridA != null && gridB != null) {
            val period = gridA.periodSamples
            val beatOut = period * outRate / rateOutgoing
            val blends = if (decision.blendBeats >= 32) listOf(32, 16) else listOf(16)
            for (blend in blends) {
                val total = blend + TAIL_BEATS
                val lengthOut = (total * beatOut).roundToInt()
                // The first boundary that leaves room for the blend before it and the tail after it.
                val boundary = nextBoundary(gridA, a.origin, earliest + blend * period)
                val swapFrame = boundary
                val start = swapFrame - blend * period
                val end = start + lengthOut.toDouble() * rateOutgoing / outRate
                if (start < earliest || end > latestEnd) {
                    // A later boundary leaves less room, so only the first one can fit.
                    continue
                }
                val enter = b.origin ?: gridB.nextDownbeat(firstIncoming)
                if (enter < firstIncoming) continue
                return MixPlan(outgoing, incoming, start, enter, lengthOut, decision.speedIncoming, MixKind.BEAT, blend, TAIL_BEATS, blend.toDouble() / total)
            }
            return null
        }
        val lengthOut = (SOFT_SECONDS * outRate).toInt()
        val span = lengthOut.toDouble() * rateOutgoing / outRate
        val start = latestEnd - span
        if (start < earliest) return null
        return MixPlan(outgoing, incoming, start, firstIncoming, lengthOut, 1.0, MixKind.SOFT, 8, 8, 0.5)
    }

    /** The first phrase boundary (or downbeat, without a known origin) at or after [sample]. */
    fun nextBoundary(grid: BeatAnalysis, origin: Double?, sample: Double): Double {
        if (origin == null) return grid.nextDownbeat(sample)
        val period = grid.periodSamples
        val block = PHRASE_BEATS * period
        val k = ceil((sample - origin) / block - 1e-9)
        return origin + k * block
    }
}
