package io.github.yyan115.opentune.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a short piece of audio into the levels of frequency bands, for the spectrum analyzer.
 *
 * The transform is a real-input FFT: the [SIZE] real samples are packed into a complex FFT of half the size and split
 * afterwards, which halves the work of a plain complex transform. Every table is computed once, and the overload that
 * takes buffers allocates nothing, so it can run many times a second.
 */
object Spectrum {
    /** The number of samples that one analysis reads. It must be a power of two. 2048 samples resolve about 21 Hz at 44.1 kHz. */
    const val SIZE = 2048
    private const val HALF = SIZE / 2

    private val window = FloatArray(SIZE) { (0.5 - 0.5 * cos(2 * PI * it / (SIZE - 1))).toFloat() }
    /** Twiddles of the half-size complex FFT. */
    private val fftCos = FloatArray(HALF / 2) { cos(2 * PI * it / HALF).toFloat() }
    private val fftSin = FloatArray(HALF / 2) { -sin(2 * PI * it / HALF).toFloat() }
    /** Twiddles that split the half-size result into the spectrum of the real signal. */
    private val splitCos = FloatArray(HALF) { cos(2 * PI * it / SIZE).toFloat() }
    private val splitSin = FloatArray(HALF) { -sin(2 * PI * it / SIZE).toFloat() }
    private val reversed = IntArray(HALF).also { table ->
        val bits = Integer.numberOfTrailingZeros(HALF)
        for (i in 0 until HALF) table[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** A full scale sine gives a magnitude of SIZE / 4 with the Hann window. Its power is the 0 dB reference. */
    private val referencePower = (SIZE / 4f) * (SIZE / 4f)
    private const val FLOOR_DB = -84f
    private const val TEN_OVER_LN10 = 4.3429448f

    private val binCache = ConcurrentHashMap<String, IntArray>()

    /** The first and last bin of each band, cached per configuration so that no power or logarithm runs in the real-time loop. */
    private fun ranges(sampleRate: Int, count: Int, lowHz: Float, top: Float): IntArray = binCache.getOrPut("$sampleRate:$count:$lowHz:$top") {
        val result = IntArray(count * 2)
        val binWidth = sampleRate.toFloat() / SIZE
        val ratio = (top / lowHz).toDouble()
        for (band in 0 until count) {
            val from = lowHz * ratio.pow(band.toDouble() / count).toFloat()
            val to = lowHz * ratio.pow((band + 1.0) / count).toFloat()
            val first = (from / binWidth).toInt().coerceIn(1, HALF - 1)
            result[band * 2] = first
            result[band * 2 + 1] = (to / binWidth).toInt().coerceIn(first, HALF - 1)
        }
        result
    }

    /** The frequency in hertz at the middle of [band] of [count], for labels. */
    fun bandHz(band: Int, count: Int, lowHz: Float, highHz: Float): Float =
        lowHz * (highHz / lowHz).toDouble().pow((band + .5) / count).toFloat()

    /**
     * The level of each of [count] bands between [lowHz] and [highHz], spaced like musical pitch, from 0 at -84 dB to 1 at full scale.
     * [samples] holds [SIZE] samples of one channel and [sampleRate] is their rate.
     */
    fun bands(samples: FloatArray, sampleRate: Int, count: Int = 32, lowHz: Float = 40f, highHz: Float = 16_000f): FloatArray =
        bands(samples, sampleRate, FloatArray(count), count, lowHz, highHz)

    /**
     * The zero-allocation overload for real-time views. [output] receives the levels, and [reBuffer], [imBuffer] and [powerBuffer]
     * are working space of at least [SIZE] / 2 values that the caller keeps between calls.
     */
    fun bands(
        samples: FloatArray,
        sampleRate: Int,
        output: FloatArray,
        count: Int = output.size,
        lowHz: Float = 40f,
        highHz: Float = 16_000f,
        reBuffer: FloatArray? = null,
        imBuffer: FloatArray? = null,
        powerBuffer: FloatArray? = null,
    ): FloatArray {
        require(samples.size >= SIZE) { "At least $SIZE samples are needed" }
        val power = powerBuffer ?: FloatArray(HALF)
        powerSpectrum(samples, power, reBuffer ?: FloatArray(HALF), imBuffer ?: FloatArray(HALF))
        val top = minOf(highHz, sampleRate / 2f)
        val ranges = ranges(sampleRate, count, lowHz, top)
        for (band in 0 until count) {
            // The power of the band is the sum of its bins, so that wide high bands are not quieter than narrow low ones.
            var sum = 0f
            for (bin in ranges[band * 2]..ranges[band * 2 + 1]) sum += power[bin]
            output[band] = level(sum)
        }
        return output
    }

    /** Maps a power to the 0..1 scale of the analyzer. */
    fun level(power: Float): Float {
        if (power <= 0f) return 0f
        val db = TEN_OVER_LN10 * ln(power / referencePower)
        return ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
    }

    /**
     * The power of each bin from 0 to [SIZE] / 2 - 1 into [power]. The bins are [SIZE] / sampleRate hertz apart.
     * [re] and [im] are working space of [SIZE] / 2 values.
     */
    fun powerSpectrum(samples: FloatArray, power: FloatArray, re: FloatArray, im: FloatArray) {
        // Pack even samples as the real part and odd samples as the imaginary part, in bit-reversed order.
        for (i in 0 until HALF) {
            val j = reversed[i]
            re[j] = samples[2 * i] * window[2 * i]
            im[j] = samples[2 * i + 1] * window[2 * i + 1]
        }
        var length = 2
        while (length <= HALF) {
            val half = length shr 1
            val step = HALF / length
            var start = 0
            while (start < HALF) {
                for (k in 0 until half) {
                    val c = fftCos[k * step]; val s = fftSin[k * step]
                    val a = start + k; val b = a + half
                    val tr = re[b] * c - im[b] * s; val ti = re[b] * s + im[b] * c
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                start += length
            }
            length = length shl 1
        }
        power[0] = 0f
        for (k in 1 until HALF) {
            val a = re[k]; val b = im[k]; val c = re[HALF - k]; val d = im[HALF - k]
            val er = (a + c) * .5f; val ei = (b - d) * .5f
            val or = (b + d) * .5f; val oi = (c - a) * .5f
            val wr = splitCos[k]; val wi = splitSin[k]
            val xr = er + wr * or - wi * oi
            val xi = ei + wr * oi + wi * or
            power[k] = xr * xr + xi * xi
        }
    }
}
