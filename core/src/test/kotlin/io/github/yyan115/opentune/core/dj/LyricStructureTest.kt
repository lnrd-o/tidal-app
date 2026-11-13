package io.github.yyan115.opentune.core.dj

import io.github.yyan115.opentune.core.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricStructureTest {
    private fun block(startMs: Long, texts: List<String>, gapMs: Long = 3_500L) = texts.mapIndexed { i, t -> LyricLine(startMs + i * gapMs, t) }
    private fun end(lines: List<LyricLine>) = lines.last().timeMs + 3_500L

    private val verse1 = listOf("walking down the empty street", "streetlights paint the evening gold", "i can hear my own heartbeat", "stories that i never told")
    private val verse2 = listOf("morning comes and we are gone", "ghosts of laughter in the hall", "every window faces east", "waiting for the light to fall")
    private val chorus = listOf("take me home tonight", "hold me close and keep me warm", "we will dance until the dawn", "take me home tonight")
    private val bridge = listOf("and if the world should fall apart", "i will find you in the dark", "one more time")
    private val outro = listOf("take me home", "take me home")

    /** verse, chorus, verse, chorus, bridge, chorus, with 6 s of silence between the parts. */
    private fun song(gap: Long = 6_000L): List<LyricLine> {
        val lines = ArrayList<LyricLine>()
        var at = 10_000L
        for (part in listOf(verse1, chorus, verse2, chorus, bridge, chorus)) {
            val b = block(at, part); lines.addAll(b); at = end(b) + gap
        }
        return lines
    }

    @Test fun theChorusIsTheStretchThatComesBackAndTheVersesAreOnceOnly() {
        val lines = song()
        val sections = LyricStructure.detect(lines, end(lines) + 20_000L)
        val kinds = sections.map { it.kind }
        println(sections.joinToString { "${it.kind} ${it.startMs / 1000}s" })
        assertEquals("three choruses", 3, kinds.count { it == CueKind.CHORUS })
        assertEquals("two verses", 2, kinds.count { it == CueKind.VERSE })
        assertEquals("a bridge", 1, kinds.count { it == CueKind.BRIDGE })
        // The first chorus starts at its first line, the verse before it at its own.
        assertEquals(10_000L, sections.first { it.kind == CueKind.VERSE }.startMs)
        val bridgeStart = lines.first { it.text == bridge.first() }.timeMs
        assertEquals(bridgeStart, sections.first { it.kind == CueKind.BRIDGE }.startMs)
        // The order of the song is kept.
        assertEquals(listOf(CueKind.VERSE, CueKind.CHORUS, CueKind.VERSE, CueKind.CHORUS, CueKind.BRIDGE, CueKind.CHORUS), kinds)
    }

    @Test fun aSongSungWithoutPausesIsFoundToo() {
        val lines = song(gap = 0L)
        val kinds = LyricStructure.detect(lines, end(lines) + 20_000L).map { it.kind }
        assertEquals(3, kinds.count { it == CueKind.CHORUS }); assertEquals(1, kinds.count { it == CueKind.BRIDGE })
    }

    @Test fun aChorusThatChangesALineIsStillTheChorus() {
        val changed = chorus.dropLast(1) + "take me home again"
        val lines = ArrayList<LyricLine>(); var at = 10_000L
        for (part in listOf(verse1, chorus, verse2, changed)) { val b = block(at, part); lines.addAll(b); at = end(b) + 6_000L }
        val kinds = LyricStructure.detect(lines, at + 20_000L).map { it.kind }
        assertEquals(2, kinds.count { it == CueKind.CHORUS })
    }

    @Test fun aLongHoleBetweenLinesIsABreak() {
        val lines = ArrayList<LyricLine>(song())
        // Move everything after the second verse 25 s later, leaving a solo.
        val cut = lines.first { it.text == chorus.first() && lines.indexOf(it) > 8 }.timeMs - 100
        val shifted = lines.map { if (it.timeMs >= cut) LyricLine(it.timeMs + 25_000L, it.text) else it }
        val sections = LyricStructure.detect(shifted, shifted.last().timeMs + 20_000L)
        assertEquals("one break", 1, sections.count { it.kind == CueKind.BREAK })
    }

    @Test fun lyricsWithNoLineThatComesBackHaveNoStructure() {
        val lines = block(0, List(12) { "a line that is sung once number $it with words" })
        assertTrue(LyricStructure.detect(lines, 60_000L).isEmpty())
        assertTrue(LyricStructure.detect(emptyList(), 60_000L).isEmpty())
        assertTrue("too few lines", LyricStructure.detect(song().take(5), 60_000L).isEmpty())
    }

    @Test fun fillersAreNotMatched() {
        // A song that only says "yeah" and "oh" over and over has no chorus to find.
        val lines = block(0, List(14) { if (it % 2 == 0) "yeah" else "oh" })
        assertTrue(LyricStructure.detect(lines, 90_000L).isEmpty())
    }

    @Test fun aTimeSnapsToTheBarsOfTheSong() {
        // 120 bpm: a bar is 2 s. A line at 16.7 s is near bar 8 (16 s), which is on a block of four.
        assertEquals(16_000L, LyricStructure.snapToBar(16_700L, 0.0, 2.0))
        // A line at 11.1 s is bar 6 (12 s): not within a bar of a block of four (8 or 12... 12 is bar 6? bars 4 and 8 are blocks).
        assertEquals(12_000L, LyricStructure.snapToBar(11_100L, 0.0, 2.0))
        // With an origin of 3 s the bars begin at 3, 5, 7...
        assertEquals(19_000L, LyricStructure.snapToBar(18_800L, 3.0, 2.0))
    }

    @Test fun theWordsReplaceTheHarmonyForVersesChorusesAndTheBridge() {
        // The song of StructureTest has its choruses at bars 24, 56 and 80 and its bridge at bar 72, at 128 bpm. Words that agree move nothing, and words that
        // differ from what the harmony found are believed.
        val bar = 4 * 60.0 / 128.0
        val features = ArrayList<SliceFeatures>()
        repeat((104 * bar / SectionAccumulator.SLICE_SECONDS).toInt()) { features.add(SliceFeatures(-42.0, -30.0, 4.0)) }
        fun at(b: Int) = (b * bar * 1000).toLong()
        val lines = ArrayList<LyricLine>()
        fun put(startBar: Int, texts: List<String>) { texts.forEachIndexed { i, t -> lines.add(LyricLine(at(startBar) + i * 3_000L, t)) } }
        put(8, verse1); put(24, chorus); put(40, verse2); put(56, chorus); put(72, bridge); put(80, chorus)
        val cues = SectionDetector.detect(features, 128.0, 0.0, 4, lines)
        println(cues.joinToString { "${it.kind} ${"%.1f".format(it.atMs / 1000.0 / bar)}" })
        val choruses = cues.filter { it.kind == CueKind.CHORUS }.map { it.atMs / 1000.0 / bar }
        assertEquals(listOf(24.0, 56.0, 80.0), choruses.map { Math.round(it).toDouble() })
        assertEquals(72.0, cues.first { it.kind == CueKind.BRIDGE }.atMs / 1000.0 / bar, 1.0)
        assertEquals(2, cues.count { it.kind == CueKind.VERSE })
    }
}
