package io.github.yyan115.opentune.core.dj.neural

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The input that the Beat This! network was trained on, computed exactly as its own code computes it (torchaudio's MelSpectrogram in LogMelSpect):
 * mono audio at 22,050 Hz, a periodic Hann window of 1024 samples, a hop of 441 (50 frames a second), frames centred with reflected edges, the magnitude
 * divided by 32 (the square root of the window length), 128 Slaney mel bands from 30 Hz to 11 kHz ([filterbank], 513 rows by 128), and log(1 + 1000 x).
 */
class LogMel(private val filterbank: FloatArray) {
    init { require(filterbank.size == BINS * MELS) { "the filterbank must be $BINS by $MELS" } }

    private val window = DoubleArray(N_FFT) { 0.5 - 0.5 * cos(2 * PI * it / N_FFT) }
    private val cosTable = DoubleArray(N_FFT / 2) { cos(2 * PI * it / N_FFT) }
    private val sinTable = DoubleArray(N_FFT / 2) { -sin(2 * PI * it / N_FFT) }
    private val bitReverse = IntArray(N_FFT).also { r ->
        val bits = 10
        for (i in 0 until N_FFT) { var x = i; var y = 0; repeat(bits) { y = (y shl 1) or (x and 1); x = x shr 1 }; r[i] = y }
    }

    /** The frames of [audio] (mono, 22,050 Hz): one row of 128 values for each 20 ms, row-major. Returns the frame count beside the data. */
    fun compute(audio: FloatArray): Pair<FloatArray, Int> {
        val frames = audio.size / HOP + 1
        val out = FloatArray(frames * MELS)
        val re = DoubleArray(N_FFT); val im = DoubleArray(N_FFT)
        val magnitude = DoubleArray(BINS)
        val half = N_FFT / 2
        for (f in 0 until frames) {
            val centre = f * HOP
            for (i in 0 until N_FFT) {
                // Reflect padding: the sample at -1 is the sample at 1, as numpy's "reflect" mode has it.
                var at = centre - half + i
                if (at < 0) at = -at
                if (at >= audio.size) at = 2 * (audio.size - 1) - at
                at = at.coerceIn(0, audio.size - 1)
                re[bitReverse[i]] = audio[at] * window[i]; im[bitReverse[i]] = 0.0
            }
            fft(re, im)
            for (k in 0 until BINS) magnitude[k] = sqrt(re[k] * re[k] + im[k] * im[k]) / 32.0
            val row = f * MELS
            for (m in 0 until MELS) {
                var sum = 0.0
                for (k in 0 until BINS) { val w = filterbank[k * MELS + m]; if (w != 0f) sum += magnitude[k] * w }
                out[row + m] = ln(1.0 + 1000.0 * sum).toFloat()
            }
        }
        return out to frames
    }

    /** An in-place radix-2 transform of [re] and [im], whose input is already in bit-reversed order. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        var size = 2
        while (size <= N_FFT) {
            val halfSize = size / 2; val step = N_FFT / size
            var start = 0
            while (start < N_FFT) {
                for (j in 0 until halfSize) {
                    val c = cosTable[j * step]; val s = sinTable[j * step]
                    val a = start + j; val b = a + halfSize
                    val tr = re[b] * c - im[b] * s; val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                start += size
            }
            size *= 2
        }
    }

    companion object {
        const val SAMPLE_RATE = 22_050
        const val N_FFT = 1024
        const val HOP = 441
        const val BINS = N_FFT / 2 + 1
        const val MELS = 128
        const val FPS = 50.0
    }
}

/** Brings mono audio to another rate with a windowed-sinc filter: slow enough to be clean, fast enough for a whole song in a second or two. */
object Resampler {
    private const val HALF_TAPS = 24

    fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return input.copyOf()
        val ratio = to.toDouble() / from
        val cutoff = min(1.0, ratio) * 0.95
        val length = floor(input.size * ratio).toInt()
        val out = FloatArray(length)
        val reach = HALF_TAPS / cutoff
        for (o in 0 until length) {
            val centre = o / ratio
            val first = max(0, (centre - reach).toInt()); val last = min(input.size - 1, (centre + reach).toInt())
            var sum = 0.0; var weights = 0.0
            for (i in first..last) {
                val x = (i - centre) * cutoff
                val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
                val w = 0.5 + 0.5 * cos(PI * (i - centre) / reach) // a Hann window over the reach
                val h = sinc * w
                sum += input[i] * h; weights += h
            }
            out[o] = if (weights != 0.0) (sum / weights).toFloat() else 0f
        }
        return out
    }
}

/** Beats and bars that the network found in a song, in seconds, with the tempo they make. */
class NeuralBeats(val beats: DoubleArray, val downbeats: DoubleArray, val bpm: Double?, val beatsPerBar: Int) {
    /** The first downbeat, or the first beat when the network found no bar. */
    val firstDownbeat: Double? get() = downbeats.firstOrNull() ?: beats.firstOrNull()
}

/** Cuts a song's spectrogram into the windows the network takes, puts its answers back together, and reads beats, bars and tempo from them. */
object BeatDecoder {
    const val WINDOW = 1500
    const val BORDER = 6

    /** The start frame of each window, as Beat This!'s split_piece places them (the last one ends with the song). */
    fun windowStarts(frames: Int, window: Int = WINDOW): IntArray {
        val step = window - 2 * BORDER
        val starts = ArrayList<Int>()
        var s = -BORDER
        while (s < frames - BORDER) { starts.add(s); s += step }
        if (frames > step && starts.isNotEmpty()) starts[starts.size - 1] = frames - (window - BORDER)
        return starts.toIntArray()
    }

    /** One window of the spectrogram from [start] (which may be negative), zero-padded to [window] frames. */
    fun window(spect: FloatArray, frames: Int, start: Int, window: Int = WINDOW): FloatArray {
        val out = FloatArray(window * LogMel.MELS)
        for (t in 0 until window) {
            val f = start + t
            if (f in 0 until frames) System.arraycopy(spect, f * LogMel.MELS, out, t * LogMel.MELS, LogMel.MELS)
        }
        return out
    }

    /**
     * Puts the answers of the windows back into one series per output, keeping for each frame the first window's answer ("keep_first"), without the
     * [BORDER] frames at each edge that the network was not trained on.
     */
    fun aggregate(windows: List<FloatArray>, starts: IntArray, frames: Int, window: Int = WINDOW): FloatArray {
        val out = FloatArray(frames) { -1000f }
        val filled = BooleanArray(frames)
        for ((w, start) in starts.withIndex()) {
            val values = windows[w]
            for (t in BORDER until window - BORDER) {
                val f = start + t
                if (f in 0 until frames && !filled[f]) { out[f] = values[t]; filled[f] = true }
            }
        }
        return out
    }

    /**
     * The beats and downbeats: frames that are the highest within three frames each way and whose logit is above 0 (a chance above one half), adjacent
     * peaks merged, each downbeat moved onto the nearest beat. The tempo is fitted to the beat times, which is finer than the 20 ms frames.
     */
    fun decode(beat: FloatArray, downbeat: FloatArray): NeuralBeats {
        val beats = peaks(beat).map { it / LogMel.FPS }.toDoubleArray()
        val downs = peaks(downbeat).map { it / LogMel.FPS }.map { d -> beats.minByOrNull { abs(it - d) } ?: d }.distinct().sorted().toDoubleArray()
        return NeuralBeats(beats, downs, tempoOf(beats), barLength(beats, downs))
    }

    private fun peaks(logits: FloatArray): List<Double> {
        val raw = ArrayList<Int>()
        for (i in logits.indices) {
            if (logits[i] <= 0f) continue
            var top = true
            for (k in max(0, i - 3)..min(logits.size - 1, i + 3)) if (logits[k] > logits[i]) { top = false; break }
            if (top) raw.add(i)
        }
        // Adjacent peak frames (a plateau) become their middle.
        val out = ArrayList<Double>()
        var i = 0
        while (i < raw.size) {
            var j = i
            while (j + 1 < raw.size && raw[j + 1] - raw[j] <= 1) j++
            out.add((raw[i] + raw[j]) / 2.0)
            i = j + 1
        }
        return out
    }

    /** The tempo from the beat times: the median gap picks the beat, then a straight line through the beats that keep to it gives the exact period. */
    fun tempoOf(beats: DoubleArray): Double? {
        if (beats.size < 8) return null
        val gaps = (1 until beats.size).map { beats[it] - beats[it - 1] }.sorted()
        val median = gaps[gaps.size / 2]
        if (median <= 0) return null
        // Number each beat by how many median gaps it is from the first, and fit time = a + period * number over those that fall near the grid.
        val numbers = beats.map { ((it - beats[0]) / median).roundToInt() }
        var n = 0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for ((k, t) in beats.withIndex()) {
            val x = numbers[k].toDouble()
            if (abs((t - beats[0]) - x * median) > median * 0.25) continue
            n++; sx += x; sy += t; sxx += x * x; sxy += x * t
        }
        if (n < 8) return 60.0 / median
        val period = (n * sxy - sx * sy) / (n * sxx - sx * sx)
        return if (period > 0) 60.0 / period else 60.0 / median
    }

    /** The beats in a bar: the most common number of beats between two downbeats, three or four. */
    private fun barLength(beats: DoubleArray, downs: DoubleArray): Int {
        if (downs.size < 3 || beats.isEmpty()) return 4
        val index = downs.map { d -> beats.indexOfFirst { abs(it - d) < 1e-6 } }.filter { it >= 0 }
        val counts = (1 until index.size).map { index[it] - index[it - 1] }.filter { it in 2..8 }
        val three = counts.count { it == 3 }; val four = counts.count { it == 4 }
        return if (three > four) 3 else 4
    }
}
