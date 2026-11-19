package io.github.lnrdo.mytidal.core

import kotlin.math.pow

/** Volume normalization from the ReplayGain values that TIDAL returns for each track and album. */
object Normalization {
    const val OFF = "off"
    const val TRACK = "track"
    const val ALBUM = "album"

    /**
     * Linear player volume for [mode]. Gains are in decibels. Loud tracks are turned down and quiet
     * tracks stay as they are, because the player cannot raise the volume above its maximum without clipping.
     */
    fun volume(mode: String, trackGain: Double?, albumGain: Double?): Float {
        val gain = when (mode) {
            TRACK -> trackGain
            ALBUM -> albumGain ?: trackGain
            else -> null
        } ?: return 1f
        if (gain.isNaN()) return 1f
        return 10.0.pow(gain.coerceIn(-40.0, 0.0) / 20.0).toFloat()
    }
}
