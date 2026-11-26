package io.github.lnrdo.mytidal.core.stems

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The four stems that the separation makes, in the order of the networks. */
enum class Stem(val id: String) {
    VOCALS("vocals"), DRUMS("drums"), BASS("bass"), OTHER("other");

    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } }
}

/**
 * A network that separates one chunk: it takes the magnitude spectrogram of the mixture, [frames] frames of [bins] bins for each of two channels, laid out
 * as [channel][frame][bin] in one array, and returns for each stem an estimate of the same size, [stem][channel][frame][bin] (its mask or its magnitude:
 * only the share of each stem among the four matters).
 */
interface StemNetwork {
    val frames: Int
    val bins: Int
    fun estimate(magnitude: FloatArray): FloatArray
}

/** Where the song comes from: [read] fills [left] and [right] with [count] samples from [offset], and with zeros where the song does not reach. */
interface StereoSource {
    val length: Int
    fun read(offset: Int, count: Int, left: FloatArray, right: FloatArray)
}

/** Where the stems go: finished samples of one channel of one stem, in order of [offset], which are never changed again. */
interface StemSink {
    fun write(stem: Int, channel: Int, offset: Int, samples: FloatArray, count: Int)
}

/** A real FFT of a power of two, in place on [re] and [im], by the iterative radix-2 method, with the twiddles made once. */
class Fft(val size: Int) {
    private val cos = DoubleArray(size / 2) { cos(2 * PI * it / size) }
    private val sin = DoubleArray(size / 2) { -sin(2 * PI * it / size) }
    private val reverse = IntArray(size).also { r ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** The forward transform; with [inverse] the inverse one, scaled by 1/size. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        for (i in 0 until size) { val j = reverse[i]; if (j > i) { val a = re[i]; re[i] = re[j]; re[j] = a; val b = im[i]; im[i] = im[j]; im[j] = b } }
        var len = 2
        while (len <= size) {
            val half = len / 2; val step = size / len
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cos[k]; val wi = if (inverse) -sin[k] else sin[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr; im[j + half] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += step
                }
                start += len
            }
            len = len shl 1
        }
        if (inverse) for (i in 0 until size) { re[i] /= size; im[i] /= size }
    }
}

/**
 * The short-time transform of Spleeter: Hann windows (periodic) of [size] samples at a hop of [hop]. The inverse overlaps the frames with the synthesis window
 * (the Hann window over the sum of the squares of the windows that overlap), which makes the transform and its inverse a perfect reconstruction.
 * Frame f of a block takes the samples from f × hop of the block.
 */
class Stft(val size: Int = 4096, val hop: Int = 1024, rectangular: Boolean = false) {
    val bins = size / 2 + 1
    private val fft = Fft(size)
    private val window = DoubleArray(size) { if (rectangular) 1.0 else .5 - .5 * cos(2 * PI * it / size) }
    private val synthesis: DoubleArray = run {
        val sum = DoubleArray(hop)
        for (i in 0 until size) sum[i % hop] += window[i] * window[i]
        DoubleArray(size) { window[it] / sum[it % hop] }
    }
    private val r = DoubleArray(size)
    private val i = DoubleArray(size)

    /** How many samples [count] frames take. */
    fun span(count: Int) = (count - 1) * hop + size

    /** The spectra of [count] frames of [block]: real and imaginary parts, [count] × [bins]. */
    fun forward(block: FloatArray, count: Int, re: FloatArray, im: FloatArray) {
        for (f in 0 until count) {
            val origin = f * hop
            for (n in 0 until size) { r[n] = block[origin + n] * window[n]; i[n] = 0.0 }
            fft.transform(r, i)
            val base = f * bins
            for (b in 0 until bins) { re[base + b] = r[b].toFloat(); im[base + b] = i[b].toFloat() }
        }
    }

    /**
     * The spectra of frames [first] until [end] of two blocks at once, [a] and [b], with one complex transform per frame: [a] goes in as the real part and
     * [b] as the imaginary part, and the two spectra are taken apart by symmetry. Half the work of two calls to [forward], and the same result.
     */
    fun forwardPair(a: FloatArray, b: FloatArray, first: Int, end: Int, reA: FloatArray, imA: FloatArray, reB: FloatArray, imB: FloatArray) {
        for (f in first until end) {
            val origin = f * hop
            for (n in 0 until size) { r[n] = a[origin + n] * window[n]; i[n] = b[origin + n] * window[n] }
            fft.transform(r, i)
            val base = f * bins
            for (k in 0 until bins) {
                val m = if (k == 0) 0 else size - k
                reA[base + k] = ((r[k] + r[m]) * .5).toFloat(); imA[base + k] = ((i[k] - i[m]) * .5).toFloat()
                reB[base + k] = ((i[k] + i[m]) * .5).toFloat(); imB[base + k] = ((r[m] - r[k]) * .5).toFloat()
            }
        }
    }

    /**
     * Overlaps [count] spectra of two signals at once into [outA] and [outB] (each [span] samples, cleared): one inverse transform per frame of A + iB, whose
     * real part is A's signal and imaginary part B's. Half the work of two calls to [inverse], and the same result.
     */
    fun inversePair(reA: FloatArray, imA: FloatArray, reB: FloatArray, imB: FloatArray, count: Int, outA: FloatArray, outB: FloatArray) {
        for (f in 0 until count) {
            val base = f * bins
            for (k in 0 until bins) { r[k] = (reA[base + k] - imB[base + k]).toDouble(); i[k] = (imA[base + k] + reB[base + k]).toDouble() }
            // Above the middle the spectra are the mirror of their halves: conj(A) + i conj(B).
            for (k in 1 until size - bins + 1) { r[size - k] = (reA[base + k] + imB[base + k]).toDouble(); i[size - k] = (reB[base + k] - imA[base + k]).toDouble() }
            fft.transform(r, i, inverse = true)
            val origin = f * hop
            for (n in 0 until size) { outA[origin + n] += (r[n] * synthesis[n]).toFloat(); outB[origin + n] += (i[n] * synthesis[n]).toFloat() }
        }
    }

    /** Overlaps [count] spectra into [out], which must hold [span] samples and have been cleared. */
    fun inverse(re: FloatArray, im: FloatArray, count: Int, out: FloatArray) {
        for (f in 0 until count) {
            val base = f * bins
            for (b in 0 until bins) { r[b] = re[base + b].toDouble(); i[b] = im[base + b].toDouble() }
            for (b in 1 until size - bins + 1) { r[size - b] = r[b]; i[size - b] = -i[b] }
            fft.transform(r, i, inverse = true)
            val origin = f * hop
            for (n in 0 until size) out[origin + n] += (r[n] * synthesis[n]).toFloat()
        }
    }
}

/**
 * Separates a song into [Stem]s with a [network], a chunk at a time, so that the memory it needs is a few chunks and not the song. The song is cut into
 * chunks of the network's frame count that overlap by [overlap] frames, and the shares of the four stems are joined across the overlap with a linear fade.
 * Each stem's share is its estimate squared over the sum of the four (a soft mask, the "separation exponent" of 2 of Spleeter), applied to the complex
 * spectrum of the mixture, so the four stems always add up to the mixture. Above the bins that the network covers, the share of the top of its range is
 * carried up, so the highs are shared as the upper mids are. The signal has [Stft.size] zeros before it, as the model was trained.
 */
private const val SHARE_FLOOR = 1e-8f

class StemSeparator(private val network: StemNetwork, private val stftSize: Int = 4096, private val hop: Int = 1024, private val overlap: Int = 32,
    /**
     * The power of the soft mask: a stem's share of a bin is its estimate to this power over the sum of the four. 2 is what Spleeter was trained with; 3 and 4
     * make the cut between stems steeper, which lets less of one stem into another, at the cost of a little more of the grainy sound of hard masks.
     */
    private val power: Int = 2,
    /** How many threads share the transforms; the network itself runs on one. */
    private val threads: Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)) {
    private fun pow(e: Float): Float = when (power) { 2 -> e * e; 3 -> e * e * e; else -> { val q = e * e; q * q } }

    /** Separates [source] into [sink]; progress goes from 0 to 1. Returns false if [cancelled] stopped it. */
    fun separate(source: StereoSource, sink: StemSink, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Boolean {
        // The source may still be arriving (a song that is being read): its length is asked again after every read, which waits for what it asks for.
        val size = stftSize
        // Each worker has its own transform, which keeps scratch space: the four stems and the two channels are done at the same time.
        val transforms = Array(4) { Stft(size, hop) }
        val bins = transforms[0].bins; val frames = network.frames; val covered = network.bins
        fun total() = (source.length + size + hop - 1) / hop
        val step = frames - overlap
        val spanFull = transforms[0].span(frames)
        val inL = FloatArray(spanFull); val inR = FloatArray(spanFull)
        val re = Array(2) { FloatArray(frames * bins) }; val im = Array(2) { FloatArray(frames * bins) }
        val magnitude = FloatArray(2 * frames * covered)
        // The spectra and the signals of the stems for each channel, [stem][channel]: both channels of a stem go through one inverse transform.
        val sr = Array(4) { Array(2) { FloatArray(frames * bins) } }; val si = Array(4) { Array(2) { FloatArray(frames * bins) } }
        val temp = Array(4) { Array(2) { FloatArray(spanFull) } }
        val denominator = FloatArray(2 * frames * covered)
        // The signal that the stems are cut into: [accOrigin] is where the first sample of each accumulator lies, in samples of the song.
        val acc = Array(4) { Array(2) { FloatArray(spanFull) } }
        var accOrigin = -size
        val fade = FloatArray(frames)
        val top = min(48, covered)
        // The crack of the drums is kept out of the voice: at a drum onset part of the voice's highs go back to the drums (see [DrumLeak]).
        val leak = DrumLeak(size)
        val leakFrom = min(leak.from, covered)
        val drumHigh = Array(2) { FloatArray(frames) }; val voiceHigh = Array(2) { FloatArray(frames) }; val moved = Array(2) { FloatArray(frames) }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads) { r -> Thread(r, "stems").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
        fun parallel(count: Int, work: (Int) -> Unit) {
            if (threads == 1) { for (i in 0 until count) work(i); return }
            pool.invokeAll((0 until count).map { i -> java.util.concurrent.Callable { work(i) } }).forEach { it.get() }
        }

        fun flush(upTo: Int) {
            // Writes the samples of the accumulators that are final: from the song's sample 0 (or what was left) up to the sample [upTo] (exclusive).
            val from = max(accOrigin, 0)
            val end = min(upTo, source.length)
            if (end > from) for (s in 0 until 4) for (c in 0 until 2) sink.write(s, c, from, acc[s][c].copyOfRange(from - accOrigin, end - accOrigin), end - from)
        }

        try {
            var start = 0; var chunk = 0
            while (true) {
                if (cancelled()) return false
                val origin = start * hop - size
                source.read(origin.coerceAtLeast(0), spanFull, inL, inR)
                // Asked after the read: a song still arriving is then either whole or longer than this chunk.
                val total = total()
                if (start >= total) break
                val count = min(frames, total - start)
                if (origin < 0) {
                    // [read] was asked from 0: move what it gave to where it belongs, behind the zeros.
                    val shift = -origin
                    System.arraycopy(inL, 0, inL, shift, spanFull - shift); java.util.Arrays.fill(inL, 0, shift, 0f)
                    System.arraycopy(inR, 0, inR, shift, spanFull - shift); java.util.Arrays.fill(inR, 0, shift, 0f)
                }
                // Both channels in one transform per frame, the frames shared by two workers.
                parallel(2) { w -> transforms[w].forwardPair(inL, inR, w * count / 2, (w + 1) * count / 2, re[0], im[0], re[1], im[1]) }
                for (c in 0 until 2) for (f in 0 until frames) for (b in 0 until covered) {
                    magnitude[(c * frames + f) * covered + b] = if (f < count) hypot(re[c][f * bins + b], im[c][f * bins + b]) else 0f
                }
                val estimate = network.estimate(magnitude)
                // A linear fade at the start of every chunk but the first and at the end of every chunk but the last: the two fades of an overlap add to one.
                for (f in 0 until count) {
                    val fadeIn = if (start > 0 && f < overlap) (f + 1f) / (overlap + 1f) else 1f
                    val fadeOut = if (start + count < total && f >= count - overlap) (count - f) / (overlap + 1f) else 1f
                    fade[f] = min(fadeIn, fadeOut)
                }
                // The sum of the squares of the four stems in each bin, shared by the four stems. A bin where every stem is silent is shared equally.
                parallel(2) { c ->
                    for (f in 0 until count) for (b in 0 until covered) {
                        var sum = 4 * SHARE_FLOOR
                        for (s in 0 until 4) sum += pow(estimate[((s * 2 + c) * frames + f) * covered + b])
                        denominator[(c * frames + f) * covered + b] = sum
                    }
                    // The energy of the drums and of the voice above the leak's first bin, frame by frame, for the onsets.
                    for (f in 0 until count) {
                        var d = 0f; var v = 0f
                        for (b in leakFrom until covered) {
                            val i = (c * frames + f) * covered + b
                            val m = magnitude[i] / denominator[i]
                            val kd = pow(estimate[((2 + c) * frames + f) * covered + b]) * m; val kv = pow(estimate[((0 + c) * frames + f) * covered + b]) * m
                            d += kd * kd; v += kv * kv
                        }
                        drumHigh[c][f] = d; voiceHigh[c][f] = v
                    }
                    leak.amounts(drumHigh[c], voiceHigh[c], count, moved[c])
                }
                parallel(4) { s ->
                    val t = transforms[s]
                    for (c in 0 until 2) {
                        for (f in 0 until count) {
                            val w = fade[f]
                            var carried = 0f
                            val base = f * bins
                            for (b in 0 until covered) {
                                val e = estimate[((s * 2 + c) * frames + f) * covered + b]
                                var k = w * (pow(e) + SHARE_FLOOR) / denominator[(c * frames + f) * covered + b]
                                val a = moved[c][f]
                                if (a > 0f && b >= leakFrom && s <= 1) {
                                    // Stem 0 is the voice and stem 1 the drums: the voice gives part of its share to the drums.
                                    if (s == 0) k *= 1 - a
                                    else k += a * w * (pow(estimate[((0 + c) * frames + f) * covered + b]) + SHARE_FLOOR) / denominator[(c * frames + f) * covered + b]
                                }
                                if (b >= covered - top) carried += k
                                sr[s][c][base + b] = re[c][base + b] * k; si[s][c][base + b] = im[c][base + b] * k
                            }
                            carried /= top
                            for (b in covered until bins) { sr[s][c][base + b] = re[c][base + b] * carried; si[s][c][base + b] = im[c][base + b] * carried }
                        }
                    }
                    java.util.Arrays.fill(temp[s][0], 0f); java.util.Arrays.fill(temp[s][1], 0f)
                    t.inversePair(sr[s][0], si[s][0], sr[s][1], si[s][1], count, temp[s][0], temp[s][1])
                    for (c in 0 until 2) { val a = acc[s][c]; val x = temp[s][c]; for (i in 0 until t.span(count)) a[i] += x[i] }
                }
                chunk++
                progress(min(1f, chunk.toFloat() / max(1, (total - overlap + step - 1) / step)))
                if (start + count >= total) break
                // The samples before the next chunk's first sample are final: write them and move the rest down.
                val advance = step * hop
                flush(accOrigin + advance)
                for (s in 0 until 4) for (c in 0 until 2) {
                    val a = acc[s][c]
                    System.arraycopy(a, advance, a, 0, spanFull - advance); java.util.Arrays.fill(a, spanFull - advance, spanFull, 0f)
                }
                accOrigin += advance
                start += step
            }
            flush(source.length)
            return true
        } finally { pool.shutdownNow() }
    }
}

/**
 * Two networks that vote. Each gives its own share of every bin to each stem (its estimate squared over the sum of the four), and the shares are averaged:
 * the two make different mistakes, so what one lets leak into the wrong stem the other mostly keeps out. The layout is that of [primary] in time (its
 * frames) and of [secondary] in frequency (its bins, which must reach at least as high): above the bins of [primary] only [secondary] votes. A
 * [secondary] with fewer frames than [primary] is run on windows of its own length that cover the chunk, joined in the middle of their overlap.
 * The result is given as estimates whose squares are the shares, so a separator that squares them and normalises gets the average back.
 */
class EnsembleNetwork(private val primary: StemNetwork, private val secondary: StemNetwork, private val weight: Float = .5f) : StemNetwork {
    override val frames = primary.frames
    override val bins = secondary.bins

    init { require(secondary.bins >= primary.bins && secondary.frames <= primary.frames) }

    override fun estimate(magnitude: FloatArray): FloatArray {
        val f = frames; val b = bins; val pb = primary.bins; val sb = secondary.bins; val sf = secondary.frames
        // The first network sees the low bins of the chunk.
        val low = FloatArray(2 * f * pb)
        for (c in 0 until 2) for (t in 0 until f) System.arraycopy(magnitude, (c * f + t) * b, low, (c * f + t) * pb, pb)
        val a = primary.estimate(low)
        val out = FloatArray(4 * 2 * f * b)
        // The second sees the whole band, in windows: the first from the start of the chunk, the last to its end, each used up to the middle of the overlap.
        val starts = if (sf >= f) listOf(0) else listOf(0, f - sf)
        val handOver = if (starts.size == 1) f else (starts[1] + sf) / 2
        for ((w, start) in starts.withIndex()) {
            val window = FloatArray(2 * sf * sb)
            for (c in 0 until 2) for (t in 0 until sf) { val src = start + t; if (src < f) System.arraycopy(magnitude, (c * f + src) * b, window, (c * sf + t) * sb, sb) }
            val e = secondary.estimate(window)
            val from = if (w == 0) 0 else min(handOver, f)
            val to = if (w == 0) min(handOver, f) else f
            for (t in from until to) {
                val local = t - start
                if (local !in 0 until sf) continue
                for (c in 0 until 2) for (k in 0 until b) {
                    var sumB = 4 * SHARE_FLOOR
                    for (s in 0 until 4) { val x = e[((s * 2 + c) * sf + local) * sb + k]; sumB += x * x }
                    var sumA = 4 * SHARE_FLOOR
                    if (k < pb) for (s in 0 until 4) { val x = a[((s * 2 + c) * f + t) * pb + k]; sumA += x * x }
                    for (s in 0 until 4) {
                        val x = e[((s * 2 + c) * sf + local) * sb + k]
                        val shareB = (x * x + SHARE_FLOOR) / sumB
                        val share = if (k < pb) { val y = a[((s * 2 + c) * f + t) * pb + k]; (1 - weight) * (y * y + SHARE_FLOOR) / sumA + weight * shareB } else shareB
                        out[((s * 2 + c) * f + t) * b + k] = sqrt(share)
                    }
                }
            }
        }
        return out
    }
}
