package io.github.lnrdo.mytidal.core.stems

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
class Stft(val size: Int = 4096, val hop: Int = 1024) {
    val bins = size / 2 + 1
    private val fft = Fft(size)
    private val window = DoubleArray(size) { .5 - .5 * cos(2 * PI * it / size) }
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

class StemSeparator(private val network: StemNetwork, private val stft: Stft = Stft(), private val overlap: Int = 32) {
    /** Separates [source] into [sink]; progress goes from 0 to 1. Returns false if [cancelled] stopped it. */
    fun separate(source: StereoSource, sink: StemSink, progress: (Float) -> Unit = {}, cancelled: () -> Boolean = { false }): Boolean {
        val n = source.length
        val bins = stft.bins; val frames = network.frames; val covered = network.bins
        val hop = stft.hop; val size = stft.size
        val total = (n + size + hop - 1) / hop
        val step = frames - overlap
        val chunks = max(1, (total - overlap + step - 1) / step)
        val spanFull = stft.span(frames)
        val inL = FloatArray(spanFull); val inR = FloatArray(spanFull)
        val re = Array(2) { FloatArray(frames * bins) }; val im = Array(2) { FloatArray(frames * bins) }
        val magnitude = FloatArray(2 * frames * covered)
        val sr = FloatArray(frames * bins); val si = FloatArray(frames * bins)
        val temp = FloatArray(spanFull)
        // The signal that the stems are cut into: [accOrigin] is where the first sample of each accumulator lies, in samples of the song.
        val acc = Array(4) { Array(2) { FloatArray(spanFull) } }
        var accOrigin = -size
        val flushed = Array(4) { IntArray(2) }
        val fade = FloatArray(frames)
        val top = min(48, covered)

        fun flush(upTo: Int) {
            // Writes the samples of the accumulators that are final: from the song's sample 0 (or what was left) up to the sample [upTo] (exclusive).
            val from = max(accOrigin, 0)
            val end = min(upTo, n)
            if (end > from) for (s in 0 until 4) for (c in 0 until 2) {
                sink.write(s, c, from, acc[s][c].copyOfRange(from - accOrigin, end - accOrigin), end - from)
                flushed[s][c] = end
            }
        }

        var start = 0; var chunk = 0
        while (start < total) {
            if (cancelled()) return false
            val count = min(frames, total - start)
            val origin = start * hop - size
            source.read(origin.coerceAtLeast(0), spanFull, inL, inR).also {
                // The block starts [origin] samples into the song; before sample 0 the signal is zeros.
            }
            if (origin < 0) {
                // [read] was asked from 0: move what it gave to where it belongs, behind the zeros.
                val shift = -origin
                System.arraycopy(inL, 0, inL, shift, spanFull - shift); java.util.Arrays.fill(inL, 0, shift, 0f)
                System.arraycopy(inR, 0, inR, shift, spanFull - shift); java.util.Arrays.fill(inR, 0, shift, 0f)
            }
            stft.forward(inL, count, re[0], im[0]); stft.forward(inR, count, re[1], im[1])
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
            for (s in 0 until 4) for (c in 0 until 2) {
                for (f in 0 until count) {
                    val w = fade[f]
                    var carried = 0f
                    for (b in 0 until covered) {
                        // A bin where every stem is silent (a network that clips to zero does this) is shared equally, so that nothing is lost.
                        var sum = 4 * SHARE_FLOOR
                        for (t in 0 until 4) { val e = estimate[((t * 2 + c) * frames + f) * covered + b]; sum += e * e }
                        val e = estimate[((s * 2 + c) * frames + f) * covered + b]
                        val k = w * (e * e + SHARE_FLOOR) / sum
                        if (b >= covered - top) carried += k
                        sr[f * bins + b] = re[c][f * bins + b] * k; si[f * bins + b] = im[c][f * bins + b] * k
                    }
                    carried /= top
                    for (b in covered until bins) { sr[f * bins + b] = re[c][f * bins + b] * carried; si[f * bins + b] = im[c][f * bins + b] * carried }
                }
                java.util.Arrays.fill(temp, 0f)
                stft.inverse(sr, si, count, temp)
                val a = acc[s][c]
                for (i in 0 until stft.span(count)) a[i] += temp[i]
            }
            chunk++
            progress(chunk.toFloat() / chunks)
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
        flush(n)
        return true
    }
}
