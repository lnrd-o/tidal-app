package io.github.lnrdo.mytidal.core.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongPhasesTest {
    @Test fun aSongWithVersesAndChorusesIsCutIntoPhases() {
        val cues = listOf(Cue(CueKind.INTRO, 0), Cue(CueKind.VERSE, 10_000), Cue(CueKind.CHORUS, 40_000), Cue(CueKind.VERSE, 70_000), Cue(CueKind.CHORUS, 100_000),
            Cue(CueKind.BRIDGE, 130_000), Cue(CueKind.CHORUS, 150_000), Cue(CueKind.OUTRO, 180_000))
        val phases = SongPhases.build(cues, 200_000)
        assertEquals(listOf(CueKind.INTRO, CueKind.VERSE, CueKind.CHORUS, CueKind.VERSE, CueKind.CHORUS, CueKind.BRIDGE, CueKind.CHORUS, CueKind.OUTRO), phases.map { it.kind })
        // They follow one another and cover the whole song.
        assertEquals(0L, phases.first().startMs); assertEquals(200_000L, phases.last().endMs)
        for (i in 1 until phases.size) assertEquals(phases[i - 1].endMs, phases[i].startMs)
        assertEquals(PhaseLevel.ENERGETIC, phases[2].level)
        assertEquals(PhaseLevel.SOFT, phases[1].level); assertEquals(PhaseLevel.SOFT, phases[5].level)
    }

    @Test fun aDropThatIsAlsoAChorusIsShownAsTheDrop() {
        val phases = SongPhases.build(listOf(Cue(CueKind.INTRO, 0), Cue(CueKind.CHORUS, 60_000), Cue(CueKind.DROP, 60_200), Cue(CueKind.OUTRO, 150_000)), 180_000)
        assertEquals(listOf(CueKind.INTRO, CueKind.DROP, CueKind.OUTRO), phases.map { it.kind })
    }

    @Test fun aSongThatStartsLateBeginsWithASoftIntro() {
        val phases = SongPhases.build(listOf(Cue(CueKind.VERSE, 8_000), Cue(CueKind.CHORUS, 40_000)), 90_000)
        assertEquals(CueKind.INTRO, phases.first().kind); assertEquals(8_000L, phases.first().endMs)
    }

    @Test fun theEdgesOfTheSoundAreNotPhasesAndNoCuesMeansNoPhases() {
        assertTrue(SongPhases.build(listOf(Cue(CueKind.START, 3_000), Cue(CueKind.END, 170_000)), 180_000).isEmpty())
        assertTrue(SongPhases.build(emptyList(), 180_000).isEmpty())
        assertTrue(SongPhases.build(listOf(Cue(CueKind.CHORUS, 5_000)), 0).isEmpty())
    }

    @Test fun thePhaseAtAPositionIsFound() {
        val phases = SongPhases.build(listOf(Cue(CueKind.INTRO, 0), Cue(CueKind.CHORUS, 30_000)), 90_000)
        assertEquals(CueKind.INTRO, SongPhases.at(phases, 10_000)!!.kind)
        assertEquals(CueKind.CHORUS, SongPhases.at(phases, 30_000)!!.kind)
        assertEquals(CueKind.CHORUS, SongPhases.at(phases, 89_999)!!.kind)
        assertNull(SongPhases.at(phases, 90_000))
    }
}
