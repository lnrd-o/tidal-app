package io.github.lnrdo.mytidal.core.stems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ScnetTest {
    private class ArraySource(val left: FloatArray, val right: FloatArray) : StereoSource {
        override val length = left.size
        override fun read(offset: Int, count: Int, left: FloatArray, right: FloatArray) {
            for (i in 0 until count) { val p = offset + i; left[i] = if (p < length) this.left[p] else 0f; right[i] = if (p < length) this.right[p] else 0f }
        }
    }

    private class ArraySink(n: Int) : StemSink {
        val out = Array(4) { Array(2) { FloatArray(n) } }
        val written = Array(4) { IntArray(2) }
        var inOrder = true
        override fun write(stem: Int, channel: Int, offset: Int, samples: FloatArray, count: Int) {
            if (offset != written[stem][channel]) inOrder = false
            System.arraycopy(samples, 0, out[stem][channel], offset, count)
            written[stem][channel] = offset + count
        }
    }

    /** Gives the first network stem (drums) its input, and the second (bass) half of it: the others are silent. */
    private class Echo(override val frames: Int = 18) : ScnetNetwork {
        override val bins = 2049
        override fun run(input: FloatArray): FloatArray {
            val plane = bins * frames
            val out = FloatArray(16 * plane)
            for (k in 0 until 4) for (i in 0 until plane) { out[k * plane + i] = input[k * plane + i]; out[(4 + k) * plane + i] = input[k * plane + i] / 2 }
            return out
        }
    }

    private fun signal(n: Int, seed: Int) = FloatArray(n) { i -> (.4 * sin(2 * PI * 220.0 * i / 44100 + seed) + .2 * sin(2 * PI * 3100.0 * i / 44100) + .1 * sin(2 * PI * 61.0 * i * seed / 44100)).toFloat() }

    @Test fun anEchoComesBackWhole() {
        // Several chunks (18 frames are 17 hops, with an overlap of 4), a length that is not a multiple of anything.
        val n = 70_000
        val l = signal(n, 1); val r = signal(n, 2)
        val sink = ArraySink(n)
        assertTrue(ScnetSeparator(Echo(), overlapHops = 4, threads = 2).separate(ArraySource(l, r), sink))
        assertTrue(sink.inOrder)
        var worst = 0f; var worstHalf = 0f
        for (i in 0 until n) {
            worst = maxOf(worst, abs(sink.out[Stem.DRUMS.ordinal][0][i] - l[i]), abs(sink.out[Stem.DRUMS.ordinal][1][i] - r[i]))
            worstHalf = maxOf(worstHalf, abs(sink.out[Stem.BASS.ordinal][0][i] - l[i] / 2))
        }
        assertEquals(0f, worst, 2e-3f)
        // The network gives back (half of the normalised input) times the deviation plus the mean, so half of the mixture is off by half the mean of the spectrum.
        assertEquals(0f, worstHalf, 0.05f)
        for (s in 0 until 4) for (c in 0 until 2) assertEquals(n, sink.written[s][c])
        // The silent stems stay silent.
        assertTrue(sink.out[Stem.VOCALS.ordinal][0].all { abs(it) < 0.05f })
    }
}
