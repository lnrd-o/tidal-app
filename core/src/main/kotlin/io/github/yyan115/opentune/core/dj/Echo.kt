package io.github.yyan115.opentune.core.dj

import kotlin.math.PI
import kotlin.math.exp

/**
 * A stereo ping-pong echo for the wash-out of a transition: the outgoing song is sent into it, and its repeats bounce from one side to the other,
 * each one a little darker than the last because the feedback passes through a low-pass filter, while the new song takes over under them.
 * It is made for one thread, and nothing is allocated for each frame.
 */
class EchoTail(private val rate: Int) {
    private val size = rate * 2
    private val left = FloatArray(size)
    private val right = FloatArray(size)
    private var write = 0
    private var delay = rate * 3 / 8
    private var feedback = 0.58f
    private var lowLeft = 0f
    private var lowRight = 0f
    private val damping = (1 - exp(-2 * PI * 3_200.0 / rate)).toFloat()

    /** The echo of the last frame fed: the sound that was sent [delay] frames ago and its repeats. */
    var outLeft = 0f
        private set
    var outRight = 0f
        private set

    /** Sets the time between repeats, in frames (up to two seconds), and how much of each repeat comes back, below one. */
    fun configure(delayFrames: Int, feedback: Float) {
        delay = delayFrames.coerceIn(1, size - 1)
        this.feedback = feedback.coerceIn(0f, 0.9f)
    }

    fun reset() {
        left.fill(0f); right.fill(0f); write = 0; lowLeft = 0f; lowRight = 0f; outLeft = 0f; outRight = 0f
    }

    /** Feeds one frame, and sets [outLeft] and [outRight] to the echo that is heard now. Feed zeros to let the echo ring out. */
    fun process(inLeft: Float, inRight: Float) {
        var read = write - delay
        if (read < 0) read += size
        val dl = left[read]; val dr = right[read]
        outLeft = dl; outRight = dr
        // The repeats cross over: what came out on the left goes back in on the right. The loop darkens them.
        lowLeft += (dl - lowLeft) * damping
        lowRight += (dr - lowRight) * damping
        left[write] = (inLeft + inRight) * 0.5f + lowRight * feedback
        right[write] = lowLeft * feedback
        if (++write == size) write = 0
    }
}

/**
 * The echo of a soft transition, for songs whose tempos or grooves do not match: the outgoing song is sent into the echo while the transition runs
 * (between [sendFrom] and [sendTo] of its length) and its repeats go on ringing after it has left. [delayFrames] is counted in output frames.
 */
class EchoPlan(val delayFrames: Int, val feedback: Float = 0.62f, val sendFrom: Double = 0.02, val sendTo: Double = 0.90) {
    /** How much of the outgoing song goes into the echo at the progress [u] of the transition, from 0 to 1. It opens and closes with a short ramp. */
    fun send(u: Double): Double {
        val ramp = 0.06
        return when {
            u < sendFrom -> 0.0
            u < sendFrom + ramp -> (u - sendFrom) / ramp
            u <= sendTo -> 1.0
            u < sendTo + ramp -> 1.0 - (u - sendTo) / ramp
            else -> 0.0
        }
    }
}
