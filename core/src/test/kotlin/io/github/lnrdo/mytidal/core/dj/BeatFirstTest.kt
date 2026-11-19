package io.github.lnrdo.mytidal.core.dj

import org.junit.Assert.assertEquals
import org.junit.Test

class BeatFirstTest {
    private fun beat(bpm: Double) = BeatAnalysis(bpm, 44_100 * 60.0 / bpm, 0.0, 0, 0.9f, 4, true)

    private fun summary(bpm: Double?, energy: Double) = TrackSummary(bpm?.let { beat(it) }, "8A", energy, null)

    @Test fun aSongThatMixesOnTheBeatGoesBeforeASoftBlend() {
        val out = summary(120.0, 6.0)
        val soft = Candidate(1, summary(null, 6.0), 44_100)
        val beatSong = Candidate(2, summary(121.0, 6.5), 44_100)
        val picked = MixPlanner.choose(out, 44_100, listOf(soft, beatSong), nextIndex = 1, minMatch = 0.0, beatFirst = true)
        assertEquals(2, picked!!.index)
    }
}
