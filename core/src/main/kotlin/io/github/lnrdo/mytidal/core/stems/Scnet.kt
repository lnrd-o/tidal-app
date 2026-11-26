package io.github.lnrdo.mytidal.core.stems

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The core of SCNet: the normalised spectrogram of a stereo chunk in, the four separated spectrograms out. Both are complex numbers as channels, [bins] by
 * [frames]: the input is [4][bins][frames] (left real, left imaginary, right real, right imaginary); the output is [4 stems][4][bins][frames], with the
 * stems in the order of the network (drums, bass, other, vocals).
 */
interface ScnetNetwork {
    val frames: Int
    val bins: Int
    fun run(input: FloatArray): FloatArray
}

/**
 * Separates a song with SCNet the way that SCNet does it: the transform has a rectangular window (what it was trained with), the chunk is normalised by the
 * mean and the deviation of its spectrogram, and the network gives the spectrum of each stem, with its own phase, which is brought back to samples. Unlike
 * [StemSeparator] the four stems do not add up to the mixture exactly: the network estimates them, and it does that better than a mask can.
 * The song is cut into chunks of [ScnetNetwork.frames] frames that overlap by [overlapHops] hops, joined with a linear fade.
 */
class ScnetSeparator(private val network: ScnetNetwork, private val size: Int = 4096, private val hop: Int = 1024, private val overlapHops: Int = 44,
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)) {
    /** The stems of the network, in its order, as indexes of [Stem]. */
    private val target = intArrayOf(Stem.DRUMS.ordinal, Stem.BASS.ordinal, Stem.OTHER.ordinal, Stem.VOCALS.ordinal)

    fun separate(source: StereoSource, sink: StemSink, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Boolean {
        val n = source.length
        val frames = network.frames; val bins = network.bins
        require(bins == size / 2 + 1)
        val pad = size / 2
        val length = (frames - 1) * hop           // the samples of a chunk
        val span = length + size                  // with the context that the first and the last frame look at
        val overlap = overlapHops * hop
        val step = length - overlap
        val chunks = max(1, (n - overlap + step - 1) / step)
        val transforms = Array(4) { Stft(size, hop, rectangular = true) }
        val scale = sqrt(size.toDouble()).toFloat()
        val inL = FloatArray(span); val inR = FloatArray(span)
        val re = Array(2) { FloatArray(frames * bins) }; val im = Array(2) { FloatArray(frames * bins) }
        val x = FloatArray(4 * bins * frames)
        val sr = Array(4) { FloatArray(frames * bins) }; val si = Array(4) { FloatArray(frames * bins) }
        val temp = Array(4) { FloatArray(span) }
        val acc = Array(4) { Array(2) { FloatArray(length) } }
        // How many of the four frames that cover a sample reach it: the first and the last samples of a chunk have fewer.
        val weight = FloatArray(length) { i -> val p = i + pad; min(4, min(p / hop + 1, (span - 1 - p) / hop + 1)).toFloat() / 4f }
        val fade = FloatArray(length) { 1f }
        // The crack of the drums is kept out of the voice (see [DrumLeak]): in the network's order the drums are stem 0 and the voice stem 3.
        val leak = DrumLeak(size)
        val drumHigh = FloatArray(frames); val voiceHigh = FloatArray(frames); val moved = FloatArray(frames)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads) { r -> Thread(r, "scnet").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
        fun parallel(count: Int, work: (Int) -> Unit) {
            if (threads == 1) { for (i in 0 until count) work(i); return }
            pool.invokeAll((0 until count).map { i -> java.util.concurrent.Callable { work(i) } }).forEach { it.get() }
        }
        var accOrigin = 0
        fun flush(upTo: Int) {
            val end = min(upTo, n)
            if (end > accOrigin) for (s in 0 until 4) for (c in 0 until 2) sink.write(target[s], c, accOrigin, acc[s][c].copyOfRange(0, end - accOrigin), end - accOrigin)
        }
        try {
            var start = 0; var chunk = 0
            while (true) {
                if (cancelled()) return false
                val last = start + length >= n
                // The chunk and its context; what lies before the song or after it is silence.
                val from = start - pad
                source.read(from.coerceAtLeast(0), span, inL, inR)
                if (from < 0) {
                    val shift = -from
                    System.arraycopy(inL, 0, inL, shift, span - shift); java.util.Arrays.fill(inL, 0, shift, 0f)
                    System.arraycopy(inR, 0, inR, shift, span - shift); java.util.Arrays.fill(inR, 0, shift, 0f)
                }
                parallel(2) { c -> transforms[c].forward(if (c == 0) inL else inR, frames, re[c], im[c]) }
                var sum = 0.0; var squares = 0.0
                for (c in 0 until 2) for (f in 0 until frames) for (b in 0 until bins) {
                    val r = re[c][f * bins + b] / scale; val i = im[c][f * bins + b] / scale
                    x[((c * 2) * bins + b) * frames + f] = r; x[((c * 2 + 1) * bins + b) * frames + f] = i
                    sum += r + i; squares += r.toDouble() * r + i.toDouble() * i
                }
                val count = x.size.toDouble()
                val mean = sum / count
                val deviation = sqrt(max(0.0, (squares - count * mean * mean) / (count - 1)))
                val m = mean.toFloat(); val d = deviation.toFloat()
                val divisor = 1e-5f + d
                for (i in x.indices) x[i] = (x[i] - m) / divisor
                val out = network.run(x)
                for (i in 0 until length) {
                    val fadeIn = if (start > 0 && i < overlap) (i + .5f) / overlap else 1f
                    val fadeOut = if (!last && i >= length - overlap) (length - i - .5f) / overlap else 1f
                    fade[i] = min(fadeIn, fadeOut) / weight[i]
                }
                for (c in 0 until 2) {
                    parallel(4) { s ->
                        for (f in 0 until frames) for (b in 0 until bins) {
                            sr[s][f * bins + b] = (out[((s * 4 + c * 2) * bins + b) * frames + f] * d + m) * scale
                            si[s][f * bins + b] = (out[((s * 4 + c * 2 + 1) * bins + b) * frames + f] * d + m) * scale
                        }
                    }
                    for (f in 0 until frames) {
                        var dh = 0f; var vh = 0f
                        for (b in leak.from until bins) {
                            val i = f * bins + b
                            dh += sr[0][i] * sr[0][i] + si[0][i] * si[0][i]; vh += sr[3][i] * sr[3][i] + si[3][i] * si[3][i]
                        }
                        drumHigh[f] = dh; voiceHigh[f] = vh
                    }
                    leak.amounts(drumHigh, voiceHigh, frames, moved)
                    for (f in 0 until frames) {
                        val a = moved[f]
                        if (a <= 0f) continue
                        for (b in leak.from until bins) {
                            val i = f * bins + b
                            sr[0][i] += a * sr[3][i]; si[0][i] += a * si[3][i]
                            sr[3][i] *= 1 - a; si[3][i] *= 1 - a
                        }
                    }
                    parallel(4) { s ->
                        java.util.Arrays.fill(temp[s], 0f)
                        transforms[s].inverse(sr[s], si[s], frames, temp[s])
                        val a = acc[s][c]; val y = temp[s]
                        for (i in 0 until length) a[i] += y[i + pad] * fade[i]
                    }
                }
                chunk++
                progress(min(1f, chunk.toFloat() / chunks))
                if (last) break
                flush(accOrigin + step)
                for (s in 0 until 4) for (c in 0 until 2) {
                    val a = acc[s][c]
                    System.arraycopy(a, step, a, 0, length - step); java.util.Arrays.fill(a, length - step, length, 0f)
                }
                accOrigin += step
                start += step
            }
            flush(n)
            return true
        } finally { pool.shutdownNow() }
    }
}
