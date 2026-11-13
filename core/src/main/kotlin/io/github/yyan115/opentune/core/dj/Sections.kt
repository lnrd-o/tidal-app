package io.github.yyan115.opentune.core.dj

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** The kinds of cue point that D'JAI finds in a song. New kinds go at the end: the stored ones are by name, and the older code counts from the start. */
enum class CueKind {
    /** The first bar of the first phrase. */
    INTRO,
    /** The last eight bars before a drop, while the energy climbs without the bass. */
    BUILD,
    /** The bass and the energy come in together after a quiet part. */
    DROP,
    /** The bass leaves for a few bars after a loud part: a break. */
    BREAK,
    /** The final quiet stretch, where a song is mixed out. */
    OUTRO,
    /** A stretch that comes back with the same harmony, quieter than the chorus: where the song tells its story. */
    VERSE,
    /** The loud stretch that comes back most, with the same harmony each time: the part that is sung along to. */
    CHORUS,
    /** A stretch that is heard once, with harmony of its own, late in the song, usually before the last chorus. */
    BRIDGE,
    /** The most intense eight bars of the song. */
    PEAK,
}

/** A cue point at [atMs] milliseconds from the start of the song. */
class Cue(val kind: CueKind, val atMs: Long)

/** A cue point as a frame of a ring, for the scheduler. */
class CueMark(val kind: CueKind, val frame: Double)

/**
 * The sound of one slice of a song: the bass, the middle and the top in decibels, the whole band, the onset strength, and the harmony as the share of each of
 * the twelve pitch classes (they add up to one, or are all zero when nothing was heard).
 */
class SliceFeatures(
    val bassDb: Double, val loudDb: Double, val flux: Double,
    val midDb: Double = 0.0, val highDb: Double = 0.0,
    val chroma: FloatArray = NO_CHROMA,
) {
    companion object { val NO_CHROMA = FloatArray(12) }
}

/**
 * Listens to a whole song while it plays and keeps a few numbers for each slice of [SLICE_SECONDS]. It is fed the power spectrum and the onset strength
 * of each frame of an [OnsetEnvelope], so it costs one pass over the bins and nothing else.
 */
class SectionAccumulator(private val rate: Int) {
    private val slices = ArrayList<SliceFeatures>()
    private val framesPerSlice = max(1, (SLICE_SECONDS * rate / OnsetEnvelope.HOP).roundToInt())
    private fun bin(hz: Double) = (hz * 2048.0 / rate).toInt()
    private val bassBins = max(3, bin(BASS_HZ))
    private val midTo = bin(2_000.0)
    private val chromaFrom = bin(220.0).coerceAtLeast(1)
    private val chromaTo = bin(2_500.0)
    private val pitchOfBin = IntArray(1024) { b ->
        if (b < chromaFrom || b > chromaTo) -1 else { val f = b.toDouble() * rate / 2048; ((12.0 * ln(f / 440.0) / ln(2.0) + 69.0).roundToInt() % 12 + 12) % 12 }
    }
    private var frames = 0
    private var bass = 0.0
    private var mid = 0.0
    private var high = 0.0
    private var all = 0.0
    private var flux = 0.0
    private val chroma = DoubleArray(12)

    /** The slices heard so far, in time order. */
    fun slices(): List<SliceFeatures> = slices

    val seconds: Double get() = slices.size * SLICE_SECONDS

    fun reset() { slices.clear(); frames = 0; bass = 0.0; mid = 0.0; high = 0.0; all = 0.0; flux = 0.0; chroma.fill(0.0) }

    /** [power] is the power spectrum of one frame and [onset] its onset strength. */
    fun add(power: FloatArray, onset: Float) {
        var b = 0.0; var m = 0.0; var h = 0.0; var t = 0.0
        val top = min(power.size - 1, bin(4_000.0))
        for (i in 2..top) {
            val p = power[i].toDouble(); t += p
            if (i <= bassBins) b += p else if (i <= midTo) m += p else h += p
            if (i in chromaFrom..chromaTo) { val pitch = pitchOfBin.getOrElse(i) { -1 }; if (pitch >= 0) chroma[pitch] += sqrt(p) }
        }
        bass += b; mid += m; high += h; all += t; flux += onset
        if (++frames == framesPerSlice) {
            val total = chroma.sum()
            val shares = FloatArray(12) { if (total > 0.0) (chroma[it] / total).toFloat() else 0f }
            slices.add(SliceFeatures(db(bass), db(all), flux / frames, db(mid), db(high), shares))
            frames = 0; bass = 0.0; mid = 0.0; high = 0.0; all = 0.0; flux = 0.0; chroma.fill(0.0)
        }
    }

    private fun db(sum: Double) = 10 * log10(sum / frames + 1e-9)

    companion object {
        const val SLICE_SECONDS = 0.5
        const val BASS_HZ = 150.0
    }
}

/**
 * Finds the sections of a song and says where they are. Two readings are put together.
 *
 * The **energy** reading splits the song into loud and quiet runs from the bass, the loudness and the onsets of each bar, and finds the intro, the build, the
 * drop, the breaks and the outro. It is right for dance music, where a section is defined by what plays.
 *
 * The **structure** reading is for songs with verses and choruses, where a section is defined by what is played. The song is cut into blocks of four bars, each
 * block gets the harmony of eight parts of it (centred, so that the key of the song does not make every block alike), and blocks that are alike in harmony and
 * intensity are one section. A section is a run of blocks between two places where the sound changes. Sections that come back with the same harmony are a group.
 * The loud group that comes back most is the chorus, the other repeated group that comes first is the verse, the one-off with a harmony of its own late in the
 * song is the bridge, and the most intense eight bars are the peak. Sections begin on the grid of four bars from the first bar, so a cut falls on a phrase.
 */
object SectionDetector {
    private const val MIN_RUN_BARS = 4
    private const val MIN_BARS = 24
    private const val MIN_SPREAD = 0.18
    private const val MIN_BASS_RISE = 0.2
    /** Bars in a block, and the parts that a block is split into for its harmony. */
    private const val BLOCK_BARS = 4
    private const val PARTS = 8
    /** Two sections are the same group at this similarity or more. */
    private const val REPEAT = 0.62
    /** A one-off section is a bridge when it is no more alike to any other group than this. */
    private const val UNIQUE = 0.55
    /** The length of a difference of harmony, on the scale of shares that add up to one, below which it is taken for noise. */
    private const val NOISE = 0.04f

    /**
     * [originSeconds] is where the first bar begins, in seconds from the start of the slices. [beatsPerBar] is three or four.
     * The cues come back in time order, with their time in milliseconds from the start of the slices.
     */
    fun detect(slices: List<SliceFeatures>, bpm: Double, originSeconds: Double, beatsPerBar: Int = 4): List<Cue> {
        if (slices.isEmpty() || bpm < 50.0 || bpm > 220.0) return emptyList()
        val barSeconds = beatsPerBar * 60.0 / bpm
        val total = slices.size * SectionAccumulator.SLICE_SECONDS
        val bars = floor((total - originSeconds) / barSeconds).toInt()
        val intro = Cue(CueKind.INTRO, (originSeconds * 1000).roundToLong())
        if (bars < MIN_BARS) return listOf(intro)
        val features = barFeatures(slices, bars, barSeconds, originSeconds)
        val cues = ArrayList<Cue>()
        cues.add(intro)
        cues.addAll(energyCues(features, bars, barSeconds, originSeconds))
        cues.addAll(structureCues(slices, features, bars, barSeconds, originSeconds))
        return merge(cues)
    }

    /** The cues in time order, without the same kind twice at one moment. A drop and a chorus at the same moment are both kept. */
    private fun merge(cues: List<Cue>): List<Cue> =
        cues.distinctBy { it.atMs to it.kind }.sortedWith(compareBy({ it.atMs }, { it.kind.ordinal }))

    // ---- the bars ----

    /** The averages of the slices over each bar. [intensity] is how much is going on, from the loudness, the onsets, the top and the bass. */
    private class BarFeatures(
        val bassN: DoubleArray, val loudN: DoubleArray, val fluxN: DoubleArray, val intensity: DoubleArray,
    )

    private fun barFeatures(slices: List<SliceFeatures>, bars: Int, barSeconds: Double, originSeconds: Double): BarFeatures {
        val sliceSeconds = SectionAccumulator.SLICE_SECONDS
        val bass = DoubleArray(bars); val loud = DoubleArray(bars); val flux = DoubleArray(bars); val high = DoubleArray(bars)
        for (i in 0 until bars) {
            val from = originSeconds + i * barSeconds; val to = from + barSeconds
            val first = ceil((from - sliceSeconds / 2) / sliceSeconds).toInt().coerceAtLeast(0)
            val last = floor((to - sliceSeconds / 2) / sliceSeconds).toInt().coerceAtMost(slices.size - 1)
            var n = 0
            for (k in first..last) { bass[i] += slices[k].bassDb; loud[i] += slices[k].loudDb; flux[i] += slices[k].flux; high[i] += slices[k].highDb; n++ }
            if (n > 0) { bass[i] /= n; loud[i] /= n; flux[i] /= n; high[i] /= n }
            else if (i > 0) { bass[i] = bass[i - 1]; loud[i] = loud[i - 1]; flux[i] = flux[i - 1]; high[i] = high[i - 1] }
        }
        val bassN = scale(bass); val loudN = scale(loud); val fluxN = scale(flux); val highN = scale(high)
        val intensity = DoubleArray(bars) { 0.35 * loudN[it] + 0.25 * fluxN[it] + 0.2 * highN[it] + 0.2 * bassN[it] }
        return BarFeatures(bassN, loudN, fluxN, intensity)
    }

    // ---- the energy reading: intro, build, drop, break and outro ----

    private fun energyCues(f: BarFeatures, bars: Int, barSeconds: Double, originSeconds: Double): List<Cue> {
        val energy = DoubleArray(bars) { 0.5 * f.bassN[it] + 0.3 * f.loudN[it] + 0.2 * f.fluxN[it] }
        val sorted = energy.sortedArray()
        val low = sorted[(bars * 0.2).toInt()]; val high = sorted[min(bars - 1, (bars * 0.8).toInt())]
        if (high - low < MIN_SPREAD) return emptyList()
        val threshold = (low + high) / 2
        val state = BooleanArray(bars) { energy[it] >= threshold }
        mergeShortRuns(state)

        // The runs of loud and quiet bars.
        val starts = ArrayList<Int>(); val ends = ArrayList<Int>()
        var s = 0
        for (i in 1..bars) if (i == bars || state[i] != state[s]) { starts.add(s); ends.add(i); s = i }

        val cues = ArrayList<Cue>()
        fun at(bar: Int): Long = ((originSeconds + bar * barSeconds) * 1000).roundToLong()
        for (r in 1 until starts.size) {
            val start = starts[r]; val length = ends[r] - starts[r]
            val before = ends[r - 1] - starts[r - 1]
            if (state[start]) {
                // A loud run after a quiet one with the bass coming in is a drop.
                if (before < MIN_RUN_BARS) continue
                val bassBefore = mean(f.bassN, max(starts[r - 1], start - 4), start)
                val bassAfter = mean(f.bassN, start, min(ends[r], start + 4))
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
        return cues
    }

    // ---- the structure reading: verse, chorus, bridge and peak ----

    /** A run of blocks: from block [start] up to, not including, block [end]. */
    private class Section(val start: Int, val end: Int) { val length get() = end - start }

    private fun structureCues(slices: List<SliceFeatures>, f: BarFeatures, bars: Int, barSeconds: Double, originSeconds: Double): List<Cue> {
        val blocks = bars / BLOCK_BARS
        if (blocks < 6) return emptyList()
        val chroma = blockChroma(slices, blocks, barSeconds, originSeconds) ?: return emptyList()
        val intensity = DoubleArray(blocks) { b -> mean(f.intensity, b * BLOCK_BARS, (b + 1) * BLOCK_BARS) }

        // How alike two blocks are: the harmony of each of the eight parts, and how intense they are. Parts with no harmony of their own (all alike) are alike.
        val similar = Array(blocks) { DoubleArray(blocks) }
        for (b in 0 until blocks) for (c in b until blocks) {
            var dot = 0.0
            for (part in 0 until PARTS) {
                val x = chroma[b][part]; val y = chroma[c][part]
                val xZero = x.all { it == 0f }; val yZero = y.all { it == 0f }
                if (xZero && yZero) dot += 1.0 else if (!xZero && !yZero) for (k in 0 until 12) dot += x[k] * y[k]
            }
            val harmony = max(0.0, dot / PARTS)
            val s = 0.8 * harmony + 0.2 * (1.0 - abs(intensity[b] - intensity[c]))
            similar[b][c] = s; similar[c][b] = s
        }

        // The sections: a new one begins where a block is not alike the one before it.
        val novelty = DoubleArray(blocks)
        for (b in 1 until blocks) {
            val near = 1.0 - similar[b - 1][b]
            val wide = if (b >= 2 && b + 1 < blocks) 1.0 - similar[b - 2][b + 1] else near
            novelty[b] = 0.6 * near + 0.4 * wide
        }
        val mean = novelty.drop(1).average()
        val spread = sqrt(novelty.drop(1).sumOf { (it - mean) * (it - mean) } / (blocks - 1))
        val threshold = max(0.3, mean + 0.5 * spread)
        val sections = ArrayList<Section>()
        var start = 0
        for (b in 1 until blocks) if (novelty[b] >= threshold) { sections.add(Section(start, b)); start = b }
        sections.add(Section(start, blocks))
        if (sections.size < 3) return emptyList()

        // How alike two sections are: their blocks side by side, over the shorter one, a little less when the lengths differ much.
        fun sectionSimilar(p: Section, q: Section): Double {
            val n = min(p.length, q.length)
            var sum = 0.0
            for (k in 0 until n) sum += similar[p.start + k][q.start + k]
            val ratio = max(p.length, q.length).toDouble() / n
            return sum / n * (if (ratio > 2.0) 0.85 else 1.0)
        }
        // The groups: sections that come back.
        val group = IntArray(sections.size) { -1 }
        val representatives = ArrayList<Int>()
        for (i in sections.indices) {
            var best = -1; var bestSimilar = REPEAT
            for ((g, rep) in representatives.withIndex()) { val s = sectionSimilar(sections[i], sections[rep]); if (s >= bestSimilar) { bestSimilar = s; best = g } }
            if (best < 0) { representatives.add(i); group[i] = representatives.size - 1 } else group[i] = best
        }
        val count = IntArray(representatives.size)
        val groupIntensity = DoubleArray(representatives.size)
        val groupFirst = IntArray(representatives.size) { Int.MAX_VALUE }
        for (i in sections.indices) {
            val g = group[i]
            count[g]++
            groupIntensity[g] += mean(intensity, sections[i].start, sections[i].end)
            groupFirst[g] = min(groupFirst[g], i)
        }
        for (g in count.indices) groupIntensity[g] /= count[g]
        val median = intensity.sortedArray()[blocks / 2]

        val cues = ArrayList<Cue>()
        fun at(block: Int): Long = ((originSeconds + block * BLOCK_BARS * barSeconds) * 1000).roundToLong()

        // The chorus: of the groups that come back, the most intense one, helped a little by coming back often.
        val repeated = count.indices.filter { count[it] >= 2 }
        val chorus = repeated.filter { groupIntensity[it] >= median + 0.03 }.maxByOrNull { groupIntensity[it] + 0.1 * min(count[it], 4) }
        if (chorus != null) for (i in sections.indices) if (group[i] == chorus) cues.add(Cue(CueKind.CHORUS, at(sections[i].start)))
        // The verse: of the other groups that come back, the one that comes first.
        val verse = repeated.filter { it != chorus && groupIntensity[it] >= median - 0.1 }.minByOrNull { groupFirst[it] }
        if (verse != null) for (i in sections.indices) if (group[i] == verse) cues.add(Cue(CueKind.VERSE, at(sections[i].start)))
        // The bridge: a one-off in the late part of the song, with harmony that no other group has.
        var bridge = -1; var bridgeSimilar = UNIQUE
        for (i in 1 until sections.size - 1) {
            if (count[group[i]] != 1) continue
            val position = sections[i].start.toDouble() / blocks
            if (position < 0.45 || position > 0.95) continue
            val others = sections.indices.filter { group[it] != group[i] }.maxOfOrNull { sectionSimilar(sections[i], sections[it]) } ?: 0.0
            if (others < bridgeSimilar) { bridgeSimilar = others; bridge = i }
        }
        if (bridge >= 0) cues.add(Cue(CueKind.BRIDGE, at(sections[bridge].start)))
        // The peak: the two blocks in a row that are the most intense.
        var peak = 0; var peakSum = -1.0
        for (b in 0 until blocks - 1) { val sum = intensity[b] + intensity[b + 1]; if (sum > peakSum) { peakSum = sum; peak = b } }
        cues.add(Cue(CueKind.PEAK, at(peak)))
        return cues
    }

    /**
     * The harmony of each block of four bars, as [PARTS] vectors of twelve: the mean pitch shares of that part of the block, minus the mean of the whole song,
     * scaled to length one. Null when the song has no harmony to read.
     */
    private fun blockChroma(slices: List<SliceFeatures>, blocks: Int, barSeconds: Double, originSeconds: Double): Array<Array<FloatArray>>? {
        val sliceSeconds = SectionAccumulator.SLICE_SECONDS
        val mean = FloatArray(12)
        var used = 0
        for (s in slices) { if (s.chroma.sum() > 0f) { for (k in 0 until 12) mean[k] += s.chroma[k]; used++ } }
        if (used < slices.size / 2) return null
        for (k in 0 until 12) mean[k] /= used
        val blockSeconds = BLOCK_BARS * barSeconds
        return Array(blocks) { b ->
            Array(PARTS) { part ->
                val from = originSeconds + b * blockSeconds + part * blockSeconds / PARTS
                val to = from + blockSeconds / PARTS
                val first = floor(from / sliceSeconds).toInt().coerceIn(0, slices.size - 1)
                val last = max(first, ceil(to / sliceSeconds).toInt() - 1).coerceAtMost(slices.size - 1)
                val v = FloatArray(12)
                for (s in first..last) for (k in 0 until 12) v[k] += slices[s].chroma[k]
                val n = last - first + 1
                var norm = 0f
                for (k in 0 until 12) { v[k] = v[k] / n - mean[k]; norm += v[k] * v[k] }
                val length = sqrt(norm)
                // Differences that are only noise are not harmony: a part that differs from the mean by less than this is left at zero.
                if (length > NOISE) for (k in 0 until 12) v[k] /= length else v.fill(0f)
                v
            }
        }
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
