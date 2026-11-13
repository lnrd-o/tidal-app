package io.github.yyan115.opentune.core.dj

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The kinds of cue point that D'JAI finds in a song. */
enum class CueKind {
    /** The first bar of the first phrase. */
    INTRO,
    /** The last eight bars before a drop, while the energy climbs without the bass. */
    BUILD,
    /** The bass and the energy come in together after a quiet part. */
    DROP,
    /** The bass leaves for a few bars after a loud part: a break, a bridge. */
    BREAK,
    /** The final quiet stretch, where a song is mixed out. */
    OUTRO,
}

/** A cue point at [atMs] milliseconds from the start of the song. */
class Cue(val kind: CueKind, val atMs: Long)

/** A cue point as a frame of a ring, for the scheduler. */
class CueMark(val kind: CueKind, val frame: Double)

/** The sound of one slice of a song: the bass and the whole band in decibels, and the onset strength. */
class SliceFeatures(val bassDb: Double, val loudDb: Double, val flux: Double)

/**
 * Listens to a whole song while it plays and keeps a few numbers for each slice of [SLICE_SECONDS]. It is fed the power spectrum and the onset strength
 * of each frame of an [OnsetEnvelope], so it costs one pass over the bins and nothing else.
 */
class SectionAccumulator(private val rate: Int) {
    private val slices = ArrayList<SliceFeatures>()
    private val framesPerSlice = max(1, (SLICE_SECONDS * rate / OnsetEnvelope.HOP).roundToInt())
    private val bassBins = max(3, (BASS_HZ * 2048.0 / rate).toInt())
    private var frames = 0
    private var bass = 0.0
    private var all = 0.0
    private var flux = 0.0

    /** The slices heard so far, in time order. */
    fun slices(): List<SliceFeatures> = slices

    val seconds: Double get() = slices.size * SLICE_SECONDS

    fun reset() { slices.clear(); frames = 0; bass = 0.0; all = 0.0; flux = 0.0 }

    /** [power] is the power spectrum of one frame and [onset] its onset strength. */
    fun add(power: FloatArray, onset: Float) {
        var b = 0.0; var t = 0.0
        val top = min(power.size - 1, (4000.0 * 2048 / rate).toInt())
        for (bin in 2..top) { val p = power[bin].toDouble(); t += p; if (bin <= bassBins) b += p }
        bass += b; all += t; flux += onset
        if (++frames == framesPerSlice) {
            slices.add(SliceFeatures(10 * log10(bass / frames + 1e-9), 10 * log10(all / frames + 1e-9), flux / frames))
            frames = 0; bass = 0.0; all = 0.0; flux = 0.0
        }
    }

    companion object {
        const val SLICE_SECONDS = 0.5
        const val BASS_HZ = 150.0
    }
}

/**
 * Finds the sections of a dance or pop song from how its sound changes bar by bar, and says where the drop, the breaks and the outro are.
 *
 * Each bar gets one number for its energy, from its bass (half), its loudness (0.3) and its onsets (0.2), each scaled between the quietest and the loudest
 * bars of the song. Bars are loud or quiet by a threshold between the typical quiet bar and the typical loud one, and short flickers are merged
 * into their neighbours. A drop is a loud section that follows a quiet one with the bass coming in. A song that does not change enough, such as a
 * constant click, has no sections and gets no cues but the intro.
 */
object SectionDetector {
    private const val MIN_RUN_BARS = 4
    private const val MIN_BARS = 24
    private const val MIN_SPREAD = 0.18
    private const val MIN_BASS_RISE = 0.2

    /**
     * [originSeconds] is where the first bar begins, in seconds from the start of the slices. [beatsPerBar] is three or four.
     * The cues come back in time order, with their time in milliseconds from the start of the slices.
     */
    fun detect(slices: List<SliceFeatures>, bpm: Double, originSeconds: Double, beatsPerBar: Int = 4): List<Cue> {
        if (slices.isEmpty() || bpm < 50.0 || bpm > 220.0) return emptyList()
        val sliceSeconds = SectionAccumulator.SLICE_SECONDS
        val barSeconds = beatsPerBar * 60.0 / bpm
        val total = slices.size * sliceSeconds
        val bars = floor((total - originSeconds) / barSeconds).toInt()
        val intro = Cue(CueKind.INTRO, (originSeconds * 1000).roundToLong())
        if (bars < MIN_BARS) return listOf(intro)

        // Average the slices whose middle falls in each bar.
        val bass = DoubleArray(bars); val loud = DoubleArray(bars); val flux = DoubleArray(bars)
        for (i in 0 until bars) {
            val from = originSeconds + i * barSeconds; val to = from + barSeconds
            val first = ceil((from - sliceSeconds / 2) / sliceSeconds).toInt().coerceAtLeast(0)
            val last = floor((to - sliceSeconds / 2) / sliceSeconds).toInt().coerceAtMost(slices.size - 1)
            var n = 0
            for (k in first..last) { bass[i] += slices[k].bassDb; loud[i] += slices[k].loudDb; flux[i] += slices[k].flux; n++ }
            if (n > 0) { bass[i] /= n; loud[i] /= n; flux[i] /= n } else { bass[i] = bass[max(0, i - 1)]; loud[i] = loud[max(0, i - 1)]; flux[i] = flux[max(0, i - 1)] }
        }
        val bassN = scale(bass); val loudN = scale(loud); val fluxN = scale(flux)
        val energy = DoubleArray(bars) { 0.5 * bassN[it] + 0.3 * loudN[it] + 0.2 * fluxN[it] }

        val sorted = energy.sortedArray()
        val low = sorted[(bars * 0.2).toInt()]; val high = sorted[min(bars - 1, (bars * 0.8).toInt())]
        if (high - low < MIN_SPREAD) return listOf(intro)
        val threshold = (low + high) / 2
        val state = BooleanArray(bars) { energy[it] >= threshold }
        mergeShortRuns(state)

        // The runs of loud and quiet bars.
        val starts = ArrayList<Int>(); val ends = ArrayList<Int>()
        var s = 0
        for (i in 1..bars) if (i == bars || state[i] != state[s]) { starts.add(s); ends.add(i); s = i }

        val cues = arrayListOf(intro)
        fun at(bar: Int): Long = ((originSeconds + bar * barSeconds) * 1000).roundToLong()
        for (r in 1 until starts.size) {
            val start = starts[r]; val length = ends[r] - starts[r]
            val before = ends[r - 1] - starts[r - 1]
            if (state[start]) {
                // A loud run after a quiet one with the bass coming in is a drop.
                if (before < MIN_RUN_BARS) continue
                val bassBefore = mean(bassN, max(starts[r - 1], start - 4), start)
                val bassAfter = mean(bassN, start, min(ends[r], start + 4))
                if (bassAfter - bassBefore >= MIN_BASS_RISE) {
                    val drop = snapToPhrase(start, bars)
                    cues.add(Cue(CueKind.DROP, at(drop)))
                    if (before >= 12 && drop - 8 > 0) cues.add(Cue(CueKind.BUILD, at(drop - 8)))
                }
            } else if (before >= MIN_RUN_BARS) {
                // A quiet run after a loud one: the outro if it is the last, else a break.
                if (r == starts.size - 1) { if (length >= 8) cues.add(Cue(CueKind.OUTRO, at(start))) }
                else if (length >= MIN_RUN_BARS) cues.add(Cue(CueKind.BREAK, at(start)))
            }
        }
        return cues.sortedBy { it.atMs }
    }

    /** A drop that lies within a bar of a block of eight bars is moved onto it. */
    private fun snapToPhrase(bar: Int, bars: Int): Int {
        val nearest = (bar / 8.0).roundToInt() * 8
        return if (abs(nearest - bar) <= 1 && nearest in 0 until bars) nearest else bar
    }

    private fun mean(values: DoubleArray, from: Int, to: Int): Double {
        if (to <= from) return 0.0
        var sum = 0.0
        for (i in from until to) sum += values[i]
        return sum / (to - from)
    }

    /** Scales a series between its 5th and 95th percentile to 0..1, or to 0.5 when it does not vary. */
    private fun scale(values: DoubleArray): DoubleArray {
        val sorted = values.sortedArray()
        val lo = sorted[(values.size * 0.05).toInt()]; val hi = sorted[min(values.size - 1, (values.size * 0.95).toInt())]
        if (hi - lo < 1e-6) return DoubleArray(values.size) { 0.5 }
        return DoubleArray(values.size) { ((values[it] - lo) / (hi - lo)).coerceIn(0.0, 1.0) }
    }

    /** Runs shorter than [MIN_RUN_BARS] that are not at the edges take the state of their neighbours. */
    private fun mergeShortRuns(state: BooleanArray) {
        var changed = true
        var guard = 0
        while (changed && guard++ < 20) {
            changed = false
            var s = 0
            for (i in 1..state.size) {
                if (i == state.size || state[i] != state[s]) {
                    if (i - s < MIN_RUN_BARS && s > 0 && i < state.size) { for (k in s until i) state[k] = state[s - 1]; changed = true }
                    s = i
                }
            }
        }
    }
}
