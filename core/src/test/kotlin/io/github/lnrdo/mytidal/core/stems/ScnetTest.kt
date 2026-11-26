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
        override fun run(input: FloatArray): java.nio.FloatBuffer {
            val plane = bins * frames
            val out = FloatArray(16 * plane)
            for (k in 0 until 4) for (i in 0 until plane) { out[k * plane + i] = input[k * plane + i]; out[(4 + k) * plane + i] = input[k * plane + i] / 2 }
            return java.nio.FloatBuffer.wrap(out)
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
    @Test fun pairedTransformsGiveWhatSingleOnesGive() {
        val t = Stft(4096, 1024); val u = Stft(4096, 1024)
        val frames = 6
        val a = signal(t.span(frames), 3); val b = signal(t.span(frames), 4)
        val n = frames * t.bins
        val ra = FloatArray(n); val ia = FloatArray(n); val rb = FloatArray(n); val ib = FloatArray(n)
        t.forward(a, frames, ra, ia); t.forward(b, frames, rb, ib)
        val pa = FloatArray(n); val qa = FloatArray(n); val pb = FloatArray(n); val qb = FloatArray(n)
        u.forwardPair(a, b, 0, frames, pa, qa, pb, qb)
        for (k in 0 until n) { assertEquals(ra[k], pa[k], 2e-3f); assertEquals(ia[k], qa[k], 2e-3f); assertEquals(rb[k], pb[k], 2e-3f); assertEquals(ib[k], qb[k], 2e-3f) }
        val oa = FloatArray(t.span(frames)); val ob = FloatArray(t.span(frames)); t.inverse(ra, ia, frames, oa); t.inverse(rb, ib, frames, ob)
        val xa = FloatArray(t.span(frames)); val xb = FloatArray(t.span(frames)); u.inversePair(ra, ia, rb, ib, frames, xa, xb)
        for (k in oa.indices) { assertEquals(oa[k], xa[k], 1e-4f); assertEquals(ob[k], xb[k], 1e-4f) }
    }

    /** A song that arrives while it is separated, a little at a time, and whose length is first given too short, as a duration in whole seconds can be. */
    private class Arriving(private val left: FloatArray, private val right: FloatArray, private val estimate: Int) : StereoSource {
        private val lock = Object()
        @Volatile private var size = 0
        private var done = false
        init {
            Thread {
                while (size < left.size) { Thread.sleep(2); synchronized(lock) { size = minOf(left.size, size + 4000); (lock as Object).notifyAll() } }
                synchronized(lock) { done = true; (lock as Object).notifyAll() }
            }.start()
        }
        override val length: Int get() = synchronized(lock) { if (done) left.size else maxOf(size, estimate) }
        override fun read(offset: Int, count: Int, left: FloatArray, right: FloatArray) {
            synchronized(lock) { while (!done && size < offset + count) (lock as Object).wait(50) }
            for (i in 0 until count) { val p = offset + i; left[i] = if (p < size) this.left[p] else 0f; right[i] = if (p < size) this.right[p] else 0f }
        }
    }

    @Test fun aSongThatIsStillArrivingIsSeparatedWhole() {
        val n = 70_000
        val l = signal(n, 1); val r = signal(n, 2)
        val sink = ArraySink(n)
        assertTrue(ScnetSeparator(Echo(), overlapHops = 4, threads = 2).separate(Arriving(l, r, n - 9000), sink))
        for (s in 0 until 4) for (c in 0 until 2) assertEquals(n, sink.written[s][c])
        var worst = 0f
        for (i in 0 until n) worst = maxOf(worst, abs(sink.out[Stem.DRUMS.ordinal][0][i] - l[i]))
        assertEquals(0f, worst, 2e-3f)
    }
}
