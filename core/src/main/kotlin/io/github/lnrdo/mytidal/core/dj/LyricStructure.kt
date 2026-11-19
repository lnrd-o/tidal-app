package io.github.lnrdo.mytidal.core.dj

import io.github.lnrdo.mytidal.core.LyricLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A part of a song found from its words: what it is, and where it starts and ends, in milliseconds. */
class LyricSection(val kind: CueKind, val startMs: Long, val endMs: Long)

/**
 * Finds the structure of a song from its timed lyrics. Words repeat where the music repeats, so this is more reliable than listening for it:
 * - The **chorus** is the stretch of lines that comes back: lines that are sung more than once, in a run, with the runs alike (at least half of their lines in
 *   common). The group of runs that comes back most is the chorus, and each run is one chorus.
 * - What lies between the choruses is sung once. Before the first chorus it is a **verse**. Between two choruses it is a verse too, except in the last part of the
 *   song, after at least two choruses and before the last one: there it is the **bridge**.
 * - A stretch of the song with no words for a long time, between two lines, is a **break** (an instrumental, a solo).
 *
 * It needs lyrics with the time of each line. Without a chorus in the words (no run of lines comes back) there is no structure to report.
 */
object LyricStructure {
    /** Lines shorter than this, once reduced to letters, are fillers ("oh", "yeah") and are not matched. */
    private const val MIN_LETTERS = 4
    /** Two runs of lines are the same chorus when at least this share of their lines is in common. */
    private const val SAME_CHORUS = 0.5
    /** A run of repeated lines is a chorus only with this many lines, or this long (in milliseconds). */
    private const val MIN_RUN_LINES = 2
    private const val MIN_RUN_MS = 6_000L
    /** Lines that are sung with no more than this between them belong to one run. */
    private const val MAX_RUN_HOLE_MS = 15_000L
    /** A hole this long between lines, with no words, is a break. */
    private const val BREAK_MS = 14_000L
    /** The bridge lies after this share of the song, and a stretch of the song needs this many lines to be a verse or a bridge. */
    private const val BRIDGE_FROM = 0.5
    private const val MIN_STRETCH_LINES = 2
    private const val MIN_LINES = 8

    fun detect(lines: List<LyricLine>, durationMs: Long): List<LyricSection> {
        val sung = lines.filter { it.text.isNotBlank() }.sortedBy { it.timeMs }
        if (sung.size < MIN_LINES || durationMs <= 0) return emptyList()
        val keys = sung.map { normalize(it.text) }
        val count = HashMap<String, Int>()
        for (k in keys) if (k.length >= MIN_LETTERS) count[k] = (count[k] ?: 0) + 1
        val repeated = BooleanArray(sung.size) { (count[keys[it]] ?: 0) >= 2 }

        // The runs of repeated lines, allowing one line that is not repeated inside a run.
        val runs = ArrayList<IntRange>()
        var i = 0
        while (i < sung.size) {
            if (!repeated[i]) { i++; continue }
            var end = i
            var j = i + 1
            while (j < sung.size && sung[j].timeMs - sung[end].timeMs <= MAX_RUN_HOLE_MS) {
                if (repeated[j]) end = j
                else if (j + 1 < sung.size && repeated[j + 1] && j - end <= 1) { /* one line that is not repeated inside the run */ }
                else break
                j++
            }
            val run = i..end
            if (run.count() >= MIN_RUN_LINES || sung[end].timeMs - sung[i].timeMs >= MIN_RUN_MS) runs.add(run)
            i = end + 1
        }
        if (runs.isEmpty()) return emptyList()

        // The group of runs that are the same chorus: the biggest one, by how many runs, then how many lines.
        fun lineSet(run: IntRange) = run.map { keys[it] }.filter { it.length >= MIN_LETTERS }.toSet()
        fun overlap(a: Set<String>, b: Set<String>): Double = if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / min(a.size, b.size)
        val groups = ArrayList<MutableList<IntRange>>()
        for (run in runs) {
            val set = lineSet(run)
            val group = groups.firstOrNull { overlap(lineSet(it.first()), set) >= SAME_CHORUS }
            if (group != null) group.add(run) else groups.add(arrayListOf(run))
        }
        val chorus = groups.maxWithOrNull(compareBy({ it.size }, { it.sumOf { r -> r.count() } })) ?: return emptyList()
        // A chorus that is sung once, with its own lines repeated inside it, is not a structure that can be told from a repeated phrase.
        if (chorus.size < 2) return emptyList()

        fun startOf(run: IntRange) = sung[run.first].timeMs
        fun endOf(run: IntRange): Long = if (run.last + 1 < sung.size) min(sung[run.last + 1].timeMs, sung[run.last].timeMs + 6_000L) else min(durationMs, sung[run.last].timeMs + 6_000L)
        val sections = ArrayList<LyricSection>()
        val ordered = chorus.sortedBy { it.first }
        for (run in ordered) sections.add(LyricSection(CueKind.CHORUS, startOf(run), endOf(run)))

        // The stretches between the choruses.
        val bounds = ArrayList<IntRange>()
        var cursor = 0
        for (run in ordered) { if (run.first > cursor) bounds.add(cursor until run.first); cursor = run.last + 1 }
        if (cursor < sung.size) bounds.add(cursor until sung.size)
        for ((n, stretch) in bounds.withIndex()) {
            if (stretch.count() < MIN_STRETCH_LINES) continue
            val start = sung[stretch.first].timeMs
            val before = ordered.count { it.last < stretch.first }
            val after = ordered.count { it.first > stretch.last }
            val late = start.toDouble() / durationMs >= BRIDGE_FROM
            // After the last chorus the words that are left are an outro, which the music tells; between choruses in the last part of the song it is the bridge.
            val kind = when {
                after == 0 -> continue
                before >= 2 && late -> CueKind.BRIDGE
                else -> CueKind.VERSE
            }
            sections.add(LyricSection(kind, start, sung[stretch.last].timeMs))
        }
        // A bridge is one stretch: only the last candidate stays, and the others are verses.
        val bridges = sections.filter { it.kind == CueKind.BRIDGE }
        if (bridges.size > 1) {
            val keep = bridges.maxByOrNull { it.startMs }!!
            for (b in bridges) if (b !== keep) { sections.remove(b); sections.add(LyricSection(CueKind.VERSE, b.startMs, b.endMs)) }
        }
        // Breaks: a long hole between two lines, with no words.
        for (k in 1 until sung.size) {
            val hole = sung[k].timeMs - sung[k - 1].timeMs
            if (hole >= BREAK_MS) sections.add(LyricSection(CueKind.BREAK, min(sung[k - 1].timeMs + 6_000L, sung[k].timeMs), sung[k].timeMs))
        }
        return sections.sortedBy { it.startMs }
    }

    /** A line reduced to its letters, in lower case, with single spaces: "Oh, hello!" and "oh hello" are the same line. */
    private fun normalize(text: String): String {
        val out = StringBuilder()
        var space = false
        for (c in text.lowercase()) {
            if (c.isLetter()) { if (space && out.isNotEmpty()) out.append(' '); out.append(c); space = false } else if (c.isWhitespace() || c == '-') space = true
        }
        return out.toString()
    }

    /** Where a time falls on the bars of a song, as the number of the bar from the first one: the nearest bar, and a block of four when that is within a bar. */
    fun snapToBar(ms: Long, originSeconds: Double, barSeconds: Double): Long {
        if (barSeconds <= 0.0) return ms
        val bar = Math.round((ms / 1000.0 - originSeconds) / barSeconds).toInt().coerceAtLeast(0)
        val block = Math.round(bar / 4.0).toInt() * 4
        val snapped = if (abs(block - bar) <= 1) block else bar
        return Math.round((originSeconds + max(0, snapped) * barSeconds) * 1000)
    }
}
