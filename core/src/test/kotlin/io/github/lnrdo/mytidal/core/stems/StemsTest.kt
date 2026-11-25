package io.github.lnrdo.mytidal.core.stems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class StemsTest {
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

    /** A network that gives each stem a fixed share of each bin, from a function of the bin. */
    private class SharesNetwork(override val frames: Int, override val bins: Int, val share: (stem: Int, bin: Int) -> Float) : StemNetwork {
        override fun estimate(magnitude: FloatArray): FloatArray {
            val out = FloatArray(4 * 2 * frames * bins)
            for (s in 0 until 4) for (c in 0 until 2) for (f in 0 until frames) for (b in 0 until bins) out[((s * 2 + c) * frames + f) * bins + b] = share(s, b)
            return out
        }
    }

    private fun signal(n: Int, seed: Int): FloatArray {
        val rng = java.util.Random(seed.toLong())
        return FloatArray(n) { (.4 * sin(2 * PI * 220 * it / 44100.0) + .2 * sin(2 * PI * 3000 * it / 44100.0) + .1 * rng.nextGaussian()).toFloat() }
    }

    private fun maxDiff(a: FloatArray, b: FloatArray) = a.indices.maxOf { abs(a[it] - b[it]) }

    @Test fun theTransformAndItsInverseRebuildTheSignal() {
        val stft = Stft(); val n = 20_000
        val x = signal(n, 1)
        val count = (n + stft.size + stft.hop - 1) / stft.hop
        val block = FloatArray(stft.span(count)); System.arraycopy(x, 0, block, stft.size, n)
        val re = FloatArray(count * stft.bins); val im = FloatArray(count * stft.bins)
        stft.forward(block, count, re, im)
        val out = FloatArray(stft.span(count))
        stft.inverse(re, im, count, out)
        assertEquals(0.0, (0 until n).maxOf { abs(out[stft.size + it] - x[it]).toDouble() }, 2e-5)
    }

    @Test fun aStemThatTakesEverythingRebuildsTheMixAcrossChunksAndTheOthersStaySilent() {
        val n = 60_000
        val l = signal(n, 2); val r = signal(n, 3)
        val sink = ArraySink(n)
        // Chunks of 16 frames that overlap by 4, so that the 60 000 samples take several chunks and their fades are used.
        val ok = StemSeparator(SharesNetwork(16, 1024) { s, _ -> if (s == 0) 1f else 0f }, overlap = 4).separate(ArraySource(l, r), sink)
        assertTrue(ok); assertTrue(sink.inOrder)
        assertEquals(0.0, maxDiff(sink.out[0][0], l).toDouble(), 3e-4)
        assertEquals(0.0, maxDiff(sink.out[0][1], r).toDouble(), 3e-4)
        for (s in 1 until 4) assertTrue(sink.out[s][0].all { abs(it) < 1e-6f })
        for (s in 0 until 4) for (c in 0 until 2) assertEquals(n, sink.written[s][c])
    }

    @Test fun theFourStemsAddUpToTheMixEvenWhereTheNetworkCoversOnlyTheLowBins() {
        val n = 50_000
        val l = signal(n, 4); val r = signal(n, 5)
        val sink = ArraySink(n)
        // The network covers 512 bins and gives vocals the low half of them and the others shares of their own.
        StemSeparator(SharesNetwork(24, 512) { s, b -> when (s) { 0 -> if (b < 100) 1f else .1f; 1 -> if (b < 100) .2f else 1f; 2 -> .5f; else -> .3f } }, overlap = 6).separate(ArraySource(l, r), sink)
        for (c in 0 until 2) {
            val sum = FloatArray(n) { i -> sink.out[0][c][i] + sink.out[1][c][i] + sink.out[2][c][i] + sink.out[3][c][i] }
            assertEquals(0.0, maxDiff(sum, if (c == 0) l else r).toDouble(), 5e-4)
        }
    }

    @Test fun aSplitByFrequencyKeepsTheLowToneInOneStemAndTheHighToneInTheOther() {
        val n = 44_100
        val low = FloatArray(n) { (.5 * sin(2 * PI * 200 * it / 44100.0)).toFloat() }   // bin 19
        val high = FloatArray(n) { (.5 * sin(2 * PI * 5000 * it / 44100.0)).toFloat() } // bin 464
        val mix = FloatArray(n) { low[it] + high[it] }
        val sink = ArraySink(n)
        StemSeparator(SharesNetwork(32, 1024) { s, b -> when (s) { 0 -> if (b < 200) 1f else 0f; 1 -> if (b < 200) 0f else 1f; else -> 0f } }, overlap = 8).separate(ArraySource(mix, mix), sink)
        val from = 4096; val to = n - 4096
        assertEquals(0.0, (from until to).maxOf { abs(sink.out[0][0][it] - low[it]).toDouble() }, 5e-3)
        assertEquals(0.0, (from until to).maxOf { abs(sink.out[1][0][it] - high[it]).toDouble() }, 5e-3)
    }

    @Test fun aBinWhereEveryStemIsSilentIsSharedEquallyAndNothingIsLost() {
        val n = 30_000
        val l = signal(n, 10); val r = signal(n, 11)
        val sink = ArraySink(n)
        StemSeparator(SharesNetwork(16, 1024) { _, _ -> 0f }, overlap = 4).separate(ArraySource(l, r), sink)
        val sum = FloatArray(n) { i -> (0 until 4).sumOf { sink.out[it][0][i].toDouble() }.toFloat() }
        assertEquals(0.0, maxDiff(sum, l).toDouble(), 5e-4)
        assertEquals(0.0, maxDiff(sink.out[0][0], FloatArray(n) { l[it] / 4 }).toDouble(), 5e-4)
    }

    @Test fun cancellingStopsTheSeparation() {
        val n = 40_000
        val sink = ArraySink(n)
        var calls = 0
        val ok = StemSeparator(SharesNetwork(8, 1024) { s, _ -> if (s == 0) 1f else 0f }, overlap = 2).separate(ArraySource(signal(n, 6), signal(n, 7)), sink, cancelled = { ++calls > 2 })
        assertFalse(ok)
    }

    @Test fun progressRisesToOne() {
        val n = 40_000
        val seen = ArrayList<Float>()
        StemSeparator(SharesNetwork(8, 1024) { s, _ -> if (s == 0) 1f else 0f }, overlap = 2).separate(ArraySource(signal(n, 8), signal(n, 9)), ArraySink(n), progress = { seen.add(it) })
        assertTrue(seen.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(1f, seen.last(), .05f)
        assertTrue(max(0f, seen.first()) > 0f)
    }
}
