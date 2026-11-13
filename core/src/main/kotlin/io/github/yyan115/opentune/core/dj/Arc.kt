package io.github.yyan115.opentune.core.dj

import kotlin.math.abs

/**
 * The shape of a set. A good set is a wave of tension and release, and a run of loud songs tires the ears as a run of quiet ones loses them. This looks
 * at the energies of the songs that played last ([history], from 1 to 10, the oldest first) and says how well a next song of a given energy continues
 * the wave: it is penalised for prolonging a loud or a quiet plateau, and rewarded for a breather after a run of peaks and a lift after a run of lows.
 */
class EnergyArc(private val history: List<Double>) {
    /** From 0 to 1, with 0.5 when too little is known. */
    fun score(energy: Double?): Double {
        if (energy == null || history.size < 2) return 0.5
        val last = history.last()
        val high = run { var n = 0; for (e in history.asReversed()) if (e >= HIGH) n++ else break; n }
        val low = run { var n = 0; for (e in history.asReversed()) if (e <= LOW) n++ else break; n }
        var score = 0.7
        if (high >= RUN) {
            score += if (energy >= HIGH) -0.15 * (high - RUN + 1) else if (energy <= HIGH - 1.5) 0.25 else 0.0
        }
        if (low >= RUN) {
            score += if (energy <= LOW) -0.15 * (low - RUN + 1) else if (energy >= LOW + 2.0) 0.25 else 0.0
        }
        // A move of two or three steps is a wave, and no move at all in the middle of a run is the start of a plateau.
        val move = abs(energy - last)
        if (move in 1.5..3.5) score += 0.1
        else if (move < 0.7 && (high >= 2 || low >= 2)) score -= 0.05
        return score.coerceIn(0.0, 1.0)
    }

    companion object {
        /** Energies at or above this are loud, and at or below the other are quiet, on the scale of 1 to 10. */
        const val HIGH = 7.0
        const val LOW = 3.0
        /** A run of this many songs is a plateau. */
        const val RUN = 3
        /** The most songs that are remembered. */
        const val MAX = 6
    }
}
