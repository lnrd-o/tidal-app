package io.github.lnrdo.mytidal.core.dj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mixing between styles: families of genre, half and double time, soft changes on a phrase, and the edges of the sound. */
class CrossGenreTest {
    private val rate = 44_100

    private fun grid(bpm: Double, anchor: Double = 0.0, confidence: Float = 0.9f) = BeatAnalysis(bpm, 60.0 / bpm * rate, anchor, 0, confidence)

    @Test fun genreNamesAreRecognisedAsTheGenresOfTheListener() {
        val names = mapOf("Rap/Hip Hop" to MusicGenre.HIPHOP, "Dance" to MusicGenre.EDM, "Electro" to MusicGenre.EDM, "Latin Music" to MusicGenre.LATIN, "Dancehall" to MusicGenre.DANCEHALL,
            "Drum & Bass" to MusicGenre.DNB, "Soul & Funk" to MusicGenre.FUNK, "Alternative" to MusicGenre.INDIE, "Heavy Metal" to MusicGenre.METAL, "Country" to MusicGenre.COUNTRY,
            "Folk" to MusicGenre.FOLK, "Jazz" to MusicGenre.JAZZ, "Blues" to MusicGenre.BLUES, "Pop" to MusicGenre.POP, "Classical" to MusicGenre.CLASSICAL, "Reggaeton" to MusicGenre.REGGAETON,
            "Reggae" to MusicGenre.REGGAE, "Disco" to MusicGenre.DISCO, "Deep House" to MusicGenre.HOUSE, "Techno" to MusicGenre.TECHNO, "Trance" to MusicGenre.TRANCE, "R&B" to MusicGenre.RNB,
            "Trap" to MusicGenre.TRAP, "Salsa" to MusicGenre.TROPICAL, "Afrobeats" to MusicGenre.AFROBEATS, "Punk" to MusicGenre.PUNK, "Rock" to MusicGenre.ROCK, "Ambient" to MusicGenre.AMBIENT)
        for ((name, genre) in names) assertEquals(name, genre, MusicGenre.fromName(name))
        assertNull(MusicGenre.fromName("Podcasts")); assertNull(MusicGenre.fromName(null))
        // Every genre belongs to a family, and every family has a genre.
        assertEquals(GenreFamily.entries.toSet(), MusicGenre.entries.map { it.family }.toSet())
    }

    @Test fun onlyClearSoundsAreGuessed() {
        assertEquals(MusicGenre.HOUSE, MusicGenre.guess(126.0, 0.5, 7.5))
        assertEquals(MusicGenre.DNB, MusicGenre.guess(174.0, 0.5, 8.0))
        assertEquals(MusicGenre.HIPHOP, MusicGenre.guess(90.0, 0.6, 6.0))
        assertEquals(MusicGenre.AMBIENT, MusicGenre.guess(100.0, null, 2.5))
        assertNull("a mid-tempo straight song could be anything", MusicGenre.guess(108.0, 0.5, 6.0))
        assertNull(MusicGenre.guess(null, null, 6.0))
    }

    @Test fun neighboursFollowEachOtherBetterThanWorldsApart() {
        assertEquals(1.0, MusicGenre.affinity(MusicGenre.HIPHOP, MusicGenre.HIPHOP)!!, 0.0)
        assertEquals("inside a family", 0.85, MusicGenre.affinity(MusicGenre.HIPHOP, MusicGenre.TRAP)!!, 0.0)
        assertEquals(MusicGenre.affinity(MusicGenre.HIPHOP, MusicGenre.REGGAETON), MusicGenre.affinity(MusicGenre.REGGAETON, MusicGenre.HIPHOP))
        assertTrue(MusicGenre.affinity(MusicGenre.HIPHOP, MusicGenre.REGGAETON)!! > MusicGenre.affinity(MusicGenre.ROCK, MusicGenre.REGGAETON)!!)
        assertTrue("metal sits by rock", MusicGenre.affinity(MusicGenre.METAL, MusicGenre.ROCK)!! > MusicGenre.affinity(MusicGenre.METAL, MusicGenre.POP)!!)
        assertNull(MusicGenre.affinity(null, MusicGenre.POP))
    }

    @Test fun theFamilyOfStyleCountsInTheMatch() {
        val out = TrackSummary(grid(124.0), "8A", 6.0, genre = MusicGenre.HOUSE)
        val same = TrackSummary(grid(124.0), "8A", 6.0, genre = MusicGenre.HOUSE)
        val far = TrackSummary(grid(124.0), "8A", 6.0, genre = MusicGenre.ROCK)
        val unknown = TrackSummary(grid(124.0), "8A", 6.0)
        val m = { b: TrackSummary -> MixPlanner.decide(out, b, rate, rate) }
        assertTrue(m(same).match > m(far).match)
        assertEquals("unknown leaves the match as it was", m(unknown).match, MixDecision(MixKind.BEAT, 1.0, m(unknown).keyScore, null, null, energyScore = m(unknown).energyScore, tempoScore = m(unknown).tempoScore).match, 1e-9)
        // A jump between worlds that still mixes on the beat gets the short blend.
        assertEquals(32, m(same).blendBeats)
        assertEquals(16, m(far).blendBeats)
    }

    @Test fun aSongAtDoubleTempoIsMixedOnTheBeatAtHalfTime() {
        val hiphop = TrackSummary(grid(86.0), "8A", 6.5)
        val dnb = TrackSummary(grid(172.0), "8A", 7.0)
        val up = MixPlanner.decide(hiphop, dnb, rate, rate)
        assertEquals(MixKind.BEAT, up.kind)
        assertEquals(2.0, up.gridFactor, 0.0)
        assertEquals("two of its beats on one: it plays at its own speed", 1.0, up.speedIncoming, 0.01)
        val down = MixPlanner.decide(dnb, hiphop, rate, rate)
        assertEquals(MixKind.BEAT, down.kind)
        assertEquals(0.5, down.gridFactor, 0.0)
        assertEquals(1.0, down.speedIncoming, 0.01)
        // Within 6 percent at half time, and the tempo counts a little less than a mix at the same tempo.
        val near = MixPlanner.decide(hiphop, TrackSummary(grid(178.0), "8A", 7.0), rate, rate)
        assertEquals(MixKind.BEAT, near.kind)
        assertEquals(178.0 / 2 / 86.0, 1.0 / near.speedIncoming, 0.01)
        assertTrue(near.tempoScore < MixPlanner.decide(hiphop, TrackSummary(grid(86.0 * 178 / 172), "8A", 7.0), rate, rate).tempoScore)
    }

    @Test fun anUnsureGridIsNotMixedAtHalfTime() {
        val a = TrackSummary(grid(86.0, confidence = 0.35f), "8A", 6.5)
        val b = TrackSummary(grid(172.0, confidence = 0.9f), "8A", 7.0)
        assertEquals(MixKind.SOFT, MixPlanner.decide(a, b, rate, rate).kind)
    }

    @Test fun aGridReadAtHalfTimeKeepsItsDownbeats() {
        val g = BeatAnalysis(172.0, 60.0 / 172 * rate, 1_000.0, 2, 0.9f)
        val half = g.scaled(2.0)
        assertEquals(86.0, half.bpm, 1e-9)
        assertEquals(g.periodSamples * 2, half.periodSamples, 1e-9)
        // Its anchor is a downbeat of the original grid, and so are all its bars.
        assertEquals(g.nextDownbeat(1_000.0), half.anchorSample, 1e-6)
        val bar = half.nextDownbeat(50_000.0)
        assertEquals(bar, g.nextDownbeat(bar), 1e-6)
        assertTrue(g.scaled(1.0) === g)
    }

    @Test fun aSoftChangeFallsOnAPhraseAndTheIncomingDownbeatLandsThere() {
        val period = 0.5 * rate
        val a = TrackSummary(grid(120.0, 0.0), "8A", 6.0, origin = 0.0)
        val b = TrackSummary(grid(90.0, 2.0 * rate), "1B", 3.0, origin = 2.0 * rate)
        val soft = MixDecision(MixKind.SOFT, 1.0, 0.2, 120.0, 90.0)
        val plan = MixScheduler.schedule(0, 1, soft, a, b, rate, rate, 48_000, 60.0 * rate, 200.0 * rate, 0.0)!!
        val middle = plan.startOutgoing + plan.lengthOut.toDouble() * rate / 48_000 / 2
        println("soft: start ${plan.startOutgoing / rate}, middle ${middle / rate}, kind ${plan.kind}, in ${plan.startIncoming / rate}")
        val block = 32 * period
        assertEquals("the middle of the blend is on a phrase", Math.rint(middle / block), middle / block, 1e-6)
        assertTrue("and it is late in the song", middle > 150.0 * rate)
        // A downbeat of the incoming song is at the middle of the blend: its intro is too short for its first one, so the next one that leaves room.
        val landed = plan.startIncoming + (middle - plan.startOutgoing)
        val barIn = 4 * 60.0 / 90.0 * rate
        assertEquals(Math.rint((landed - 2.0 * rate) / barIn), (landed - 2.0 * rate) / barIn, 1e-6)
        assertTrue(plan.startIncoming >= 0.0)
        // With a long intro, the first phrase itself lands there.
        val longIntro = TrackSummary(grid(90.0, 10.0 * rate), "1B", 3.0, origin = 10.0 * rate)
        val second = MixScheduler.schedule(0, 1, soft, a, longIntro, rate, rate, 48_000, 60.0 * rate, 200.0 * rate, 0.0)!!
        assertEquals(10.0 * rate, second.startIncoming + (middle - second.startOutgoing), 1.0)
    }

    @Test fun aSoftChangeWithAnOutroStartsThere() {
        val a = TrackSummary(grid(120.0, 0.0), "8A", 6.0, origin = 0.0, cues = listOf(CueMark(CueKind.OUTRO, 128.0 * rate)))
        val b = TrackSummary(null, "1B", 3.0)
        val plan = MixScheduler.schedule(0, 1, MixDecision(MixKind.SOFT, 1.0, 0.2, 120.0, null), a, b, rate, rate, 48_000, 60.0 * rate, 200.0 * rate, 0.0)!!
        val middle = plan.startOutgoing + plan.lengthOut.toDouble() * rate / 48_000 / 2
        assertEquals("the first phrase from the outro", 128.0 * rate, middle, 1.0)
    }

    @Test fun silenceAtTheEndIsNotMixedInto() {
        val a = TrackSummary(null, "8A", 6.0, cues = listOf(CueMark(CueKind.END, 150.0 * rate)))
        val b = TrackSummary(null, "8A", 6.0, cues = listOf(CueMark(CueKind.START, 4.0 * rate)))
        val plan = MixScheduler.schedule(0, 1, MixDecision(MixKind.SOFT, 1.0, 1.0, null, null), a, b, rate, rate, 48_000, 60.0 * rate, 200.0 * rate, 0.0)!!
        val end = plan.startOutgoing + plan.lengthOut.toDouble() * rate / 48_000
        assertTrue("the blend ends before the silence: ${end / rate}", end <= 150.0 * rate)
        assertEquals("the incoming song starts with its sound", 4.0 * rate, plan.startIncoming, 1e-6)
    }

    @Test fun theEdgesOfTheSoundAreFound() {
        val silent = SliceFeatures(-120.0, -120.0, 0.0)
        val music = SliceFeatures(-20.0, -14.0, 8.0)
        val slices = List(6) { silent } + List(300) { music } + List(10) { silent }
        val edges = SectionDetector.soundEdges(slices)
        assertEquals(3_000L, edges.first { it.kind == CueKind.START }.atMs)
        assertEquals(153_000L, edges.first { it.kind == CueKind.END }.atMs)
        assertTrue("no edge on a file that is all sound", SectionDetector.soundEdges(List(100) { music }).isEmpty())
    }

    @Test fun aFadeAtTheEndIsTheOutro() {
        val bar = 4 * 60.0 / 128.0
        val perBar = (bar / SectionAccumulator.SLICE_SECONDS)
        val slices = ArrayList<SliceFeatures>()
        fun add(bars: Int, f: (Double) -> SliceFeatures) { val n = (bars * perBar).toInt(); for (i in 0 until n) slices.add(f(i.toDouble() / n)) }
        add(16) { SliceFeatures(-40.0, -28.0, 3.0) }
        add(48) { SliceFeatures(-22.0, -16.0, 10.0) }
        // Eight bars that slide down by 30 dB.
        add(8) { u -> SliceFeatures(-22.0 - 30 * u, -16.0 - 30 * u, 10.0 - 8 * u) }
        val cues = SectionDetector.detect(slices, 128.0, 0.0)
        val outro = cues.firstOrNull { it.kind == CueKind.OUTRO }
        assertNotNull("a fade gives an outro: ${cues.joinToString { "${it.kind} ${it.atMs / 1000.0 / bar}" }}", outro)
        assertEquals(64.0, outro!!.atMs / 1000.0 / bar, 2.0)
    }

    @Test fun genreMixesAreBuiltAndAJourneyPrefersNeighbours() {
        val songs = (0 until 7).map { SongPoint("h$it", 124.0, "8A", 5.0 + it * 0.3, MusicGenre.HOUSE) } + (0 until 3).map { SongPoint("r$it", 124.0, "8A", 6.0, MusicGenre.ROCK) }
        val mixes = AutoMixes.build(songs)
        val house = mixes.firstOrNull { (it.kind as? AutoMixKind.Genre)?.genre == MusicGenre.HOUSE }
        assertNotNull(house)
        assertEquals(7, house!!.ids.size)
        assertTrue(mixes.none { (it.kind as? AutoMixKind.Genre)?.genre == MusicGenre.ROCK })
        val e = SongPoint("e", 124.0, "8A", 6.0, MusicGenre.HOUSE)
        assertTrue(AutoMixes.transition(e, songs[1]) > AutoMixes.transition(e, songs[8]))
    }
}
