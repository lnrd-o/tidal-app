package io.github.lnrdo.mytidal.core.stems

/**
 * Keeps the crack of the drums out of the voice. A snare or a clap is short and has energy at every frequency, and a separation by masks gives part of its
 * highs to the voice: in the vocal stem it is heard as a crack. So at the frames where the drums' highs jump well above the frames before (an onset), part
 * of what the voice has above [fromHz] is given back to the drums. The two stems still add up to what they had.
 */
class DrumLeak(size: Int = 4096, rate: Int = 44_100, fromHz: Double = 2000.0) {
    /** The first bin that is looked at and moved. */
    val from = (fromHz * size / rate).toInt()

    /**
     * Fills [out] with the part of the voice's highs to move to the drums in each of [count] frames (0 to [MOST]), from the energy of the drums and of the
     * voice above [from] in each frame.
     */
    fun amounts(drums: FloatArray, voice: FloatArray, count: Int, out: FloatArray) {
        for (f in 0 until count) {
            var before = 0f; var n = 0
            for (g in maxOf(0, f - 3) until f) { before += drums[g]; n++ }
            if (n == 0) { out[f] = 0f; continue }
            before /= n
            val rise = drums[f] / (before + 1e-9f)
            // How far the drums lead the voice in the highs at that frame: where the voice has as much (it rose with them, or the mask cannot tell them
            // apart), nothing is taken from it.
            val share = ((drums[f] - voice[f]) / (drums[f] + voice[f] + 1e-12f)).coerceIn(0f, 1f)
            val onset = ((rise - RISE) / 4f).coerceIn(0f, MOST) * share
            // An onset lasts a little into the next frame.
            out[f] = maxOf(onset, if (f > 0) out[f - 1] * .5f else 0f)
        }
    }

    private companion object {
        /** The drums' highs must be this many times their level in the frames before. */
        const val RISE = 2f
        const val MOST = .8f
    }
}
