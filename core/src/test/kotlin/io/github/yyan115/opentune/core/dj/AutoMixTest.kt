package io.github.yyan115.opentune.core.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoMixTest {
    private fun song(id: String, bpm: Double?, key: String?, energy: Double?) = SongPoint(id, bpm, key, energy)

    private val songs = listOf(
        song("a", 124.0, "8A", 7.0), song("b", 125.0, "9A", 7.5), song("c", 126.0, "8A", 8.0), song("d", 123.0, "7A", 6.8), song("e", 124.5, "8B", 7.2), song("r", 127.0, "9A", 8.4),
        song("f", 122.0, "8A", 3.0), song("g", 121.0, "9A", 3.5), song("h", 120.0, "7A", 4.0), song("i", 119.0, "8A", 2.5), song("j", 118.0, "8B", 3.2), song("q", 117.0, "9A", 4.2),
        song("k", 90.0, "3A", 5.0), song("l", 92.0, "4A", 5.5), song("m", 88.0, "3A", 5.2), song("n", 91.0, "2A", 5.8), song("o", 89.0, "3B", 6.0), song("p", 180.0, "3A", 5.4),
    )

    @Test fun mixesAreBuiltByEnergyTempoAndKey() {
        val mixes = AutoMixes.build(songs)
        val kinds = mixes.map { it.kind }
        assertTrue("a journey", kinds.any { it is AutoMixKind.Journey })
        assertTrue("a calm mix", kinds.any { it is AutoMixKind.Energy && it.level == 0 })
        assertTrue("a peak mix", kinds.any { it is AutoMixKind.Energy && it.level == 2 })
        assertTrue("a tempo band around 120", kinds.any { it is AutoMixKind.Tempo && it.from == 115 })
        assertTrue("a key family around 8A", kinds.any { it is AutoMixKind.Harmonic })
        for (mix in mixes) assertTrue("${mix.kind} has ${mix.ids.size}", mix.ids.size >= AutoMixes.MIN_SONGS && mix.ids.toSet().size == mix.ids.size)
    }

    @Test fun halfAndDoubleTempoCountAsTheSameBand() {
        val slow = AutoMixes.build(songs).first { it.kind is AutoMixKind.Tempo && (it.kind as AutoMixKind.Tempo).from == 70 }
        assertTrue("the 180 bpm song is a 90 bpm song at double: ${slow.ids}", "p" in slow.ids)
    }

    @Test fun theJourneyStartsCalmAndClimbs() {
        val journey = AutoMixes.build(songs).first { it.kind is AutoMixKind.Journey }
        val energies = journey.ids.map { id -> songs.first { it.id == id }.energy!! }
        assertEquals("it starts with the calmest", 2.5, energies.first(), 0.0)
        assertTrue("it ends higher than it starts: $energies", energies.takeLast(4).average() > energies.take(4).average())
    }

    @Test fun eachNextSongIsTheBestOfThoseLeft() {
        val order = AutoMixes.order(songs.take(5), rising = false)
        for (i in 1 until order.size) {
            val last = songs.first { it.id == order[i - 1] }
            val chosen = AutoMixes.transition(last, songs.first { it.id == order[i] })
            val others = songs.take(5).filter { it.id !in order.take(i + 1) }
            for (o in others) assertTrue(chosen >= AutoMixes.transition(last, o) - 1e-9)
        }
    }

    @Test fun tooFewSongsMakeNoMix() {
        assertTrue(AutoMixes.build(songs.take(4)).isEmpty())
        assertTrue(AutoMixes.build(emptyList()).isEmpty())
        // Songs with nothing known are left out.
        assertTrue(AutoMixes.build(List(10) { song("$it", null, null, null) }).isEmpty())
    }
}
