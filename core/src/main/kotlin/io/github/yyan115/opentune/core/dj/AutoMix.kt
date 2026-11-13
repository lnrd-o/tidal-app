package io.github.yyan115.opentune.core.dj

import kotlin.math.abs
import kotlin.math.min

/** What D'JAI knows of a song, for building mixes: its id, tempo, key as a Camelot code, and energy from 1 to 10. Any of them may be missing. */
class SongPoint(val id: String, val bpm: Double?, val camelot: String?, val energy: Double?)

/** The kinds of mix that are built, each with the numbers that describe it. */
sealed class AutoMixKind {
    /** All the songs that are known, from the calmest to the most intense, as a set that builds. */
    object Journey : AutoMixKind()
    /** Songs of one energy: calm (1 to 4.5), groove (4.5 to 6.5) or peak (6.5 to 10). */
    class Energy(val level: Int) : AutoMixKind()
    /** Songs within a band of tempo, in beats per minute. */
    class Tempo(val from: Int, val to: Int) : AutoMixKind()
    /** Songs whose keys sit next to each other on the Camelot wheel, around [centre]. */
    class Harmonic(val centre: String) : AutoMixKind()
}

/** A mix: its kind and its songs, in the order that they mix best. */
class AutoMix(val kind: AutoMixKind, val ids: List<String>)

/**
 * Builds playlists from the songs that D'JAI has analysed, each in an order that gives good transitions. A mix keeps to one energy, one band of tempo or one
 * family of keys, and its songs follow one another by the best next song each time: near in tempo (also at half or double), near on the Camelot wheel and
 * near in energy. The journey takes all the songs and climbs in energy, as a set does.
 */
object AutoMixes {
    const val MIN_SONGS = 6
    const val MAX_SONGS = 40
    private val TEMPO_BANDS = listOf(70 to 99, 100 to 114, 115 to 124, 125 to 132, 133 to 180)

    fun build(songs: List<SongPoint>): List<AutoMix> {
        val mixes = ArrayList<AutoMix>()
        val known = songs.filter { it.bpm != null || it.camelot != null || it.energy != null }
        if (known.size >= MIN_SONGS) mixes.add(AutoMix(AutoMixKind.Journey, order(known, rising = true).take(MAX_SONGS)))
        // By energy.
        for (level in 0..2) {
            val members = known.filter { val e = it.energy ?: return@filter false; when (level) { 0 -> e < 4.5; 1 -> e >= 4.5 && e < 6.5; else -> e >= 6.5 } }
            if (members.size >= MIN_SONGS) mixes.add(AutoMix(AutoMixKind.Energy(level), order(members, rising = false).take(MAX_SONGS)))
        }
        // By tempo, where half and double count as the same tempo.
        for ((from, to) in TEMPO_BANDS) {
            val members = known.filter { val b = it.bpm ?: return@filter false; listOf(b, b * 2, b / 2).any { t -> t >= from && t <= to + 0.999 } }
            if (members.size >= MIN_SONGS) mixes.add(AutoMix(AutoMixKind.Tempo(from, to), order(members, rising = false).take(MAX_SONGS)))
        }
        // By families of keys: each key with its neighbours on the wheel, the largest families first, and none that is mostly another one.
        val families = known.mapNotNull { it.camelot }.distinct().map { centre -> centre to known.filter { s -> s.camelot != null && Camelot.compatibility(centre, s.camelot) >= 0.8 } }
            .sortedByDescending { it.second.size }
        val taken = HashSet<String>()
        for ((centre, members) in families) {
            if (members.size < MIN_SONGS) continue
            val fresh = members.count { it.id !in taken }
            if (fresh < members.size / 2) continue
            mixes.add(AutoMix(AutoMixKind.Harmonic(centre), order(members, rising = false).take(MAX_SONGS)))
            taken.addAll(members.map { it.id })
            if (mixes.count { it.kind is AutoMixKind.Harmonic } >= 4) break
        }
        return mixes
    }

    /**
     * How well [b] follows [a], from 0 to 1: tempo 40 percent (half and double count), key 30 percent and energy 30 percent. In a rising order a step up in
     * energy counts as well as staying, and a step down counts less.
     */
    fun transition(a: SongPoint, b: SongPoint, rising: Boolean = false): Double {
        val tempo = if (a.bpm != null && b.bpm != null) {
            val ratio = b.bpm / a.bpm
            val nearest = minOf(abs(ratio - 1), abs(ratio / 2 - 1), abs(ratio * 2 - 1))
            (1.0 - nearest / 0.08).coerceIn(0.0, 1.0)
        } else 0.5
        val key = Camelot.compatibility(a.camelot, b.camelot)
        val energy = if (a.energy != null && b.energy != null) {
            val step = b.energy - a.energy
            val gap = if (rising && step > 0) min(step, 1.5) / 3 else abs(step)
            (1.0 - gap / 4.0).coerceIn(0.0, 1.0)
        } else 0.5
        return 0.4 * tempo + 0.3 * key + 0.3 * energy
    }

    /** The songs in the order that mixes best: from the calmest one (or the middle one), each time the best next one of those left. */
    fun order(songs: List<SongPoint>, rising: Boolean): List<String> {
        if (songs.isEmpty()) return emptyList()
        val left = songs.toMutableList()
        val first = if (rising) left.minByOrNull { it.energy ?: 5.0 }!! else left.sortedBy { it.energy ?: 5.0 }[left.size / 2]
        left.remove(first)
        val out = arrayListOf(first)
        while (left.isNotEmpty()) {
            val last = out.last()
            val next = left.maxByOrNull { transition(last, it, rising) - (if (rising) 0.02 * ((it.energy ?: 5.0) - (last.energy ?: 5.0)).coerceAtMost(0.0).let { d -> -d } else 0.0) }!!
            left.remove(next); out.add(next)
        }
        return out.map { it.id }
    }
}
