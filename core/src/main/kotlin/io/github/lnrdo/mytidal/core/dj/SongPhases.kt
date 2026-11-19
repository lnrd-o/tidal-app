package io.github.lnrdo.mytidal.core.dj

/** How much is going on in a phase of a song: the loud parts that people sing along to or dance to, the parts that build, and the soft ones. */
enum class PhaseLevel { ENERGETIC, RISING, SOFT }

/** One phase of a song, from [startMs] to [endMs], named by the cue that opens it. */
class Phase(val kind: CueKind, val startMs: Long, val endMs: Long) {
    val level: PhaseLevel get() = SongPhases.levelOf(kind)
    val durationMs: Long get() = endMs - startMs
}

/** Turns the cue points of a song into consecutive phases that cover it, for drawing and for saying what part is playing. */
object SongPhases {
    /** The chorus, the drop and the peak are the energetic parts; a build rises; the rest are soft: verses, bridges, breaks, the intro and the outro. */
    fun levelOf(kind: CueKind): PhaseLevel = when (kind) {
        CueKind.CHORUS, CueKind.DROP, CueKind.PEAK -> PhaseLevel.ENERGETIC
        CueKind.BUILD -> PhaseLevel.RISING
        else -> PhaseLevel.SOFT
    }

    /** When two cues open a phase at the same moment, the one that says most wins: a drop that is also a chorus is shown as the drop. */
    private val PRIORITY = listOf(CueKind.DROP, CueKind.CHORUS, CueKind.PEAK, CueKind.BRIDGE, CueKind.BREAK, CueKind.BUILD, CueKind.VERSE, CueKind.OUTRO, CueKind.INTRO)

    /**
     * The phases of a song of [durationMs] from its [cues]. The first phase starts at 0 (a song with no cue at its start begins with a soft intro), and each
     * phase lasts until the next one opens. Cues that mark the edges of the sound ([CueKind.START], [CueKind.END]) are not phases. A song with no cue that
     * opens a phase has none.
     */
    fun build(cues: List<Cue>, durationMs: Long): List<Phase> {
        if (durationMs <= 0) return emptyList()
        val opening = cues.filter { it.kind != CueKind.START && it.kind != CueKind.END && it.atMs in 0 until durationMs }
        if (opening.isEmpty()) return emptyList()
        // One phase for each moment, named by the cue of highest priority there. Cues within a second of each other are the same moment.
        val moments = ArrayList<Pair<Long, CueKind>>()
        for (cue in opening.sortedBy { it.atMs }) {
            val last = moments.lastOrNull()
            if (last != null && cue.atMs - last.first < 1_000L) {
                if (PRIORITY.indexOf(cue.kind) < PRIORITY.indexOf(last.second)) moments[moments.size - 1] = last.first to cue.kind
            } else moments.add(cue.atMs to cue.kind)
        }
        // The peak is eight bars, not a phase that lasts to the next cue: it is cut at the next moment like any other, and a PEAK that is also the last one ends the song.
        val out = ArrayList<Phase>()
        if (moments.first().first > 0) out.add(Phase(CueKind.INTRO, 0L, moments.first().first))
        for ((i, m) in moments.withIndex()) out.add(Phase(m.second, m.first, moments.getOrNull(i + 1)?.first ?: durationMs))
        return out
    }

    /** The phase that holds [positionMs], or null. */
    fun at(phases: List<Phase>, positionMs: Long): Phase? = phases.lastOrNull { positionMs >= it.startMs && positionMs < it.endMs }
}
