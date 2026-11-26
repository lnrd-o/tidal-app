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
    /** The result, read at absolute places (the buffer's position does not matter); it is only good until the next run. */
    fun run(input: FloatArray): java.nio.FloatBuffer
}

/**
 * Separates a song with SCNet the way that SCNet does it: the transform has a rectangular window (what it was trained with), the chunk is normalised by the
 * mean and the deviation of its spectrogram, and the network gives the spectrum of each stem, with its own phase, which is brought back to samples. Unlike
 * [StemSeparator] the four stems do not add up to the mixture exactly: the network estimates them, and it does that better than a mask can.
 * The song is cut into chunks of [ScnetNetwork.frames] frames that overlap by [overlapHops] hops, joined with a linear fade.
 */
class ScnetSeparator(private val network: ScnetNetwork, private val size: Int = 4096, private val hop: Int = 1024, private val overlapHops: Int = 44,
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
    /** Where the time of each stage goes ([STAGES]), when it is to be measured. */
    private val clock: StageClock? = null) {
    /** The stems of the network, in its order, as indexes of [Stem]. */
    private val target = intArrayOf(Stem.DRUMS.ordinal, Stem.BASS.ordinal, Stem.OTHER.ordinal, Stem.VOCALS.ordinal)

    companion object {
        /** The stages that [clock] measures: waiting for the song, the forward transform, normalising, the network, unpacking, the inverse, writing. */
        val STAGES = listOf("read", "forward", "pack", "network", "unpack", "inverse", "write")
    }

    private inline fun <T> stage(index: Int, work: () -> T): T = clock?.time(index, work) ?: work()

    /**
     * Separates [source] into [sink]. The source may still be arriving (a song that is being read): its length is asked again after every chunk, and a read
     * waits for the samples that it asks for; it must not end before the length that it gave.
     */
    fun separate(source: StereoSource, sink: StemSink, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Boolean {
        val frames = network.frames; val bins = network.bins
        require(bins == size / 2 + 1)
        val pad = size / 2
        val length = (frames - 1) * hop           // the samples of a chunk
        val span = length + size                  // with the context that the first and the last frame look at
        val overlap = overlapHops * hop
        val step = length - overlap
        val transforms = Array(4) { Stft(size, hop, rectangular = true) }
        val scale = sqrt(size.toDouble()).toFloat()
        val inL = FloatArray(span); val inR = FloatArray(span)
        val re = Array(2) { FloatArray(frames * bins) }; val im = Array(2) { FloatArray(frames * bins) }
        val x = FloatArray(4 * bins * frames)
        // The spectra of the four stems, for each channel: [stem][channel].
        val sr = Array(4) { Array(2) { FloatArray(frames * bins) } }; val si = Array(4) { Array(2) { FloatArray(frames * bins) } }
        val temp = Array(4) { Array(2) { FloatArray(span) } }
        val acc = Array(4) { Array(2) { FloatArray(length) } }
        // How many of the four frames that cover a sample reach it: the first and the last samples of a chunk have fewer.
        val weight = FloatArray(length) { i -> val p = i + pad; min(4, min(p / hop + 1, (span - 1 - p) / hop + 1)).toFloat() / 4f }
        val fade = FloatArray(length) { 1f }
        // The crack of the drums is kept out of the voice (see [DrumLeak]): in the network's order the drums are stem 0 and the voice stem 3.
        val leak = DrumLeak(size)
        val drumHigh = Array(2) { FloatArray(frames) }; val voiceHigh = Array(2) { FloatArray(frames) }; val moved = Array(2) { FloatArray(frames) }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads) { r -> Thread(r, "scnet").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
        fun parallel(count: Int, work: (Int) -> Unit) {
            if (threads == 1) { for (i in 0 until count) work(i); return }
            pool.invokeAll((0 until count).map { i -> java.util.concurrent.Callable { work(i) } }).forEach { it.get() }
        }
        var accOrigin = 0
        fun flush(upTo: Int) = stage(6) {
            val end = min(upTo, source.length)
            if (end > accOrigin) for (s in 0 until 4) for (c in 0 until 2) sink.write(target[s], c, accOrigin, acc[s][c].copyOfRange(0, end - accOrigin), end - accOrigin)
        }
        try {
            var start = 0; var chunk = 0
            while (true) {
                if (cancelled()) return false
                // The chunk and its context; what lies before the song or after it is silence. A song that is still arriving is waited for.
                val from = start - pad
                stage(0) { source.read(from.coerceAtLeast(0), span, inL, inR) }
                // Asked after the read: a song still arriving is then either whole or longer than this chunk.
                val last = start + length >= source.length
                if (from < 0) {
                    val shift = -from
                    System.arraycopy(inL, 0, inL, shift, span - shift); java.util.Arrays.fill(inL, 0, shift, 0f)
                    System.arraycopy(inR, 0, inR, shift, span - shift); java.util.Arrays.fill(inR, 0, shift, 0f)
                }
                // Both channels in one transform per frame, the frames shared by two workers.
                stage(1) { parallel(2) { w -> transforms[w].forwardPair(inL, inR, w * frames / 2, (w + 1) * frames / 2, re[0], im[0], re[1], im[1]) } }
                val (m, d) = stage(2) {
                    var sum = 0.0; var squares = 0.0
                    for (c in 0 until 2) for (f in 0 until frames) for (b in 0 until bins) {
                        val r = re[c][f * bins + b] / scale; val i = im[c][f * bins + b] / scale
                        x[((c * 2) * bins + b) * frames + f] = r; x[((c * 2 + 1) * bins + b) * frames + f] = i
                        sum += r + i; squares += r.toDouble() * r + i.toDouble() * i
                    }
                    val count = x.size.toDouble()
                    val mean = sum / count
                    val deviation = sqrt(max(0.0, (squares - count * mean * mean) / (count - 1)))
                    val mf = mean.toFloat(); val df = deviation.toFloat()
                    val divisor = 1e-5f + df
                    for (i in x.indices) x[i] = (x[i] - mf) / divisor
                    mf to df
                }
                val out = stage(3) { network.run(x) }
                for (i in 0 until length) {
                    val fadeIn = if (start > 0 && i < overlap) (i + .5f) / overlap else 1f
                    val fadeOut = if (!last && i >= length - overlap) (length - i - .5f) / overlap else 1f
                    fade[i] = min(fadeIn, fadeOut) / weight[i]
                }
                stage(4) {
                    parallel(8) { job ->
                        val st = job / 2; val c = job % 2
                        val r = sr[st][c]; val q = si[st][c]
                        for (f in 0 until frames) for (b in 0 until bins) {
                            r[f * bins + b] = (out.get(((st * 4 + c * 2) * bins + b) * frames + f) * d + m) * scale
                            q[f * bins + b] = (out.get(((st * 4 + c * 2 + 1) * bins + b) * frames + f) * d + m) * scale
                        }
                    }
                    parallel(2) { c ->
                        for (f in 0 until frames) {
                            var dh = 0f; var vh = 0f
                            for (b in leak.from until bins) {
                                val i = f * bins + b
                                dh += sr[0][c][i] * sr[0][c][i] + si[0][c][i] * si[0][c][i]; vh += sr[3][c][i] * sr[3][c][i] + si[3][c][i] * si[3][c][i]
                            }
                            drumHigh[c][f] = dh; voiceHigh[c][f] = vh
                        }
                        leak.amounts(drumHigh[c], voiceHigh[c], frames, moved[c])
                        for (f in 0 until frames) {
                            val a = moved[c][f]
                            if (a <= 0f) continue
                            for (b in leak.from until bins) {
                                val i = f * bins + b
                                sr[0][c][i] += a * sr[3][c][i]; si[0][c][i] += a * si[3][c][i]
                                sr[3][c][i] *= 1 - a; si[3][c][i] *= 1 - a
                            }
                        }
                    }
                }
                // Each stem's two channels in one inverse transform per frame.
                stage(5) {
                    parallel(4) { st ->
                        java.util.Arrays.fill(temp[st][0], 0f); java.util.Arrays.fill(temp[st][1], 0f)
                        transforms[st].inversePair(sr[st][0], si[st][0], sr[st][1], si[st][1], frames, temp[st][0], temp[st][1])
                        for (c in 0 until 2) { val a = acc[st][c]; val y = temp[st][c]; for (i in 0 until length) a[i] += y[i + pad] * fade[i] }
                    }
                }
                chunk++
                val chunks = max(1, (source.length - overlap + step - 1) / step)
                progress(min(1f, chunk.toFloat() / chunks))
                if (last) break
                flush(accOrigin + step)
                for (st in 0 until 4) for (c in 0 until 2) {
                    val a = acc[st][c]
                    System.arraycopy(a, step, a, 0, length - step); java.util.Arrays.fill(a, length - step, length, 0f)
                }
                accOrigin += step
                start += step
            }
            flush(source.length)
            return true
        } finally { pool.shutdownNow() }
    }
}
