package io.github.lnrdo.mytidal.core

/**
 * The highest level that each column of the spectrum reached over the last [seconds]. The time is cut into [buckets] slices that each keep a maximum, so a peak
 * leaves the window as a whole slice at a time and the cost of a frame is one comparison per column.
 */
class PeakWindow(val columns: Int, val seconds: Float = 3f, private val buckets: Int = 12) {
    private val slices = Array(buckets) { FloatArray(columns) }
    private val sliceSeconds = seconds / buckets
    private var current = 0
    private var elapsed = 0f

    /** The highest level of each column in the window, valid after [add]. */
    val highest = FloatArray(columns)

    /** Adds the levels shown now ([levels] has [columns] values from 0 to 1) and moves the time on by [dt] seconds. */
    fun add(levels: FloatArray, dt: Float) {
        elapsed += dt
        while (elapsed >= sliceSeconds) {
            elapsed -= sliceSeconds
            current = (current + 1) % buckets
            slices[current].fill(0f)
        }
        val slice = slices[current]
        for (i in 0 until columns) {
            if (levels[i] > slice[i]) slice[i] = levels[i]
            var top = 0f
            for (b in 0 until buckets) if (slices[b][i] > top) top = slices[b][i]
            highest[i] = top
        }
    }

    fun clear() { for (s in slices) s.fill(0f); highest.fill(0f); elapsed = 0f }
}
