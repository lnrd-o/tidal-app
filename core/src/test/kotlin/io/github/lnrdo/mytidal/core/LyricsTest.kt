package io.github.lnrdo.mytidal.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    private val lrc = "[ar:Someone]\n[00:11.78] First line\n[00:13.5] Second line\n[01:02.123] Third line\n[00:30.00][02:00.00] Chorus\n[03:00.00]\nnot a timed line"

    @Test fun parsesTimedLinesInOrderAndIgnoresTags() {
        val lines = LyricsParser.parseLrc(lrc)
        assertEquals(listOf(11_780L, 13_500L, 30_000L, 62_123L, 120_000L, 180_000L), lines.map { it.timeMs })
        assertEquals("Chorus", lines[2].text)
        assertEquals("Chorus", lines[4].text)
        assertEquals("", lines.last().text)
    }

    @Test fun findsTheCurrentLine() {
        val lyrics = Lyrics(LyricsParser.parseLrc(lrc), "")
        assertEquals(-1, lyrics.lineAt(5_000))
        assertEquals(0, lyrics.lineAt(11_780))
        assertEquals(1, lyrics.lineAt(20_000))
        assertEquals(lyrics.lines.lastIndex, lyrics.lineAt(999_000))
    }

    @Test fun unsyncedLyricsFallBackToPlainText() {
        val lyrics = LyricsParser.parse(JSONObject("""{"lyrics":"One\nTwo","subtitles":"","lyricsProvider":"MUSIXMATCH","isRightToLeft":true}"""))
        assertFalse(lyrics.synced)
        assertEquals("One\nTwo", lyrics.plain)
        assertTrue(lyrics.rightToLeft)
        assertEquals("MUSIXMATCH", lyrics.provider)
    }

    @Test fun syncedLyricsWithoutPlainTextBuildItFromTheLines() {
        val lyrics = LyricsParser.parse(JSONObject("""{"subtitles":"[00:01.00] A\n[00:02.00] B"}"""))
        assertTrue(lyrics.synced)
        assertEquals("A\nB", lyrics.plain)
    }

    @Test fun emptyResponsesAreEmpty() {
        assertTrue(LyricsParser.parse(JSONObject("{}")).isEmpty)
    }

    @Test fun parsesCredits() {
        val credits = CreditsParser.parse(JSONObject("""{"items":[
            {"type":"Producer","contributors":[{"name":"A","id":5},{"name":"B"}]},
            {"type":"Empty","contributors":[]},{"type":"Mixing","contributors":[{"name":"C","id":null}]}]}"""))
        assertEquals(listOf("Producer", "Mixing"), credits.map { it.role })
        assertEquals(listOf(Contributor("A", "5"), Contributor("B", "")), credits[0].people)
        assertEquals("", credits[1].people.single().id)
    }
}
