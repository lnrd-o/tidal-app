package io.github.yyan115.opentune.core.dj

/**
 * A ring of stereo float frames that one thread writes and others read by absolute frame index.
 * The writer publishes [written] after the data, so a reader that sees a frame count also sees the frames.
 * [discontinuity] is the frame at which the last flush (a seek or a new track) happened: frames before it belong to other audio.
 */
class PcmRing(val capacity: Int, val rate: Int) {
    private val data = FloatArray(capacity * 2)

    @Volatile var written: Long = 0L
        private set
    @Volatile var discontinuity: Long = 0L
        private set

    /** Appends [frames] interleaved stereo frames from [block]. */
    fun write(block: FloatArray, frames: Int) {
        val start = written
        var pos = (start % capacity).toInt()
        var i = 0
        while (i < frames) {
            data[pos * 2] = block[i * 2]
            data[pos * 2 + 1] = block[i * 2 + 1]
            if (++pos == capacity) pos = 0
            i++
        }
        written = start + frames
    }

    /** Marks everything written so far as belonging to audio that is no longer current. */
    fun markDiscontinuity() { discontinuity = written }

    fun left(frame: Long): Float = data[(frame % capacity).toInt() * 2]
    fun right(frame: Long): Float = data[(frame % capacity).toInt() * 2 + 1]

    /** The oldest frame that is still intact, with a margin for the writer. */
    fun oldest(): Long = maxOf(discontinuity, written - capacity + MARGIN)

    companion object { const val MARGIN = 4096 }
}
