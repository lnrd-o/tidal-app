package io.github.lnrdo.mytidal.core.stems

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min

/**
 * A soft limiter for stereo: when the samples would pass [ceiling], both channels are turned down together, smoothly, a little before the peak (it looks
 * [lookAheadMs] ahead, so the output is that much late), and come back up over [releaseMs]. Where a hard clip cuts the top off a wave, which is heard as a
 * crack, this only changes the level for a moment.
 */
class Limiter(rate: Int, private val ceiling: Float = .95f, lookAheadMs: Float = 1.5f, releaseMs: Float = 80f) {
    private val ahead = maxOf(1, (rate * lookAheadMs / 1000f).toInt())
    private val left = FloatArray(ahead); private val right = FloatArray(ahead)
    private val peaks = FloatArray(ahead)
    private var at = 0
    private var gain = 1f
    private val attack = 1f - exp(-4.0 / ahead).toFloat()
    private val release = 1f - exp(-1000.0 / (releaseMs * rate)).toFloat()

    /** Limits [frames] interleaved stereo frames of [buffer] in place. */
    fun process(buffer: FloatArray, frames: Int) {
        for (n in 0 until frames) {
            val l = buffer[2 * n]; val r = buffer[2 * n + 1]
            // The sample goes into the look-ahead line; the one that comes out is the one heard now.
            val outL = left[at]; val outR = right[at]
            left[at] = l; right[at] = r; peaks[at] = maxOf(abs(l), abs(r))
            at = if (at + 1 == ahead) 0 else at + 1
            var peak = 0f
            for (p in peaks) if (p > peak) peak = p
            val target = if (peak > ceiling) ceiling / peak else 1f
            gain += (target - gain) * (if (target < gain) attack else release)
            // A last guard: what still passes the ceiling (a peak that came faster than the attack) is held at it.
            val g = min(gain, if (maxOf(abs(outL), abs(outR)) * gain > ceiling) ceiling / maxOf(abs(outL), abs(outR)) else gain)
            buffer[2 * n] = outL * g; buffer[2 * n + 1] = outR * g
        }
    }

    fun reset() { left.fill(0f); right.fill(0f); peaks.fill(0f); at = 0; gain = 1f }
}
