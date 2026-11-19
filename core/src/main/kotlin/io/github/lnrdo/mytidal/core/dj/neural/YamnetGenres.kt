package io.github.lnrdo.mytidal.core.dj.neural

import io.github.lnrdo.mytidal.core.dj.GenreFamily
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The input of YAMNet, computed as its own code computes it: mono audio at 16 kHz, frames of 25 ms every 10 ms with a periodic Hann window and no padding,
 * the magnitude of a 512-point transform (257 bins), 64 mel bands from 125 Hz to 7.5 kHz ([filterbank], 257 rows by 64, as TensorFlow builds it), and the log of
 * the band plus 0.001. The log-mel frames are cut into patches of 96 frames every 48 frames, the examples that the network takes.
 */
class YamnetFeatures(private val filterbank: FloatArray) {
    init { require(filterbank.size == BINS * BANDS) { "the filterbank must be $BINS by $BANDS" } }

    private val window = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2 * PI * it / WINDOW) }
    private val cosTable = DoubleArray(FFT / 2) { cos(2 * PI * it / FFT) }
    private val sinTable = DoubleArray(FFT / 2) { -kotlin.math.sin(2 * PI * it / FFT) }
    private val bitReverse = IntArray(FFT).also { r -> for (i in 0 until FFT) { var x = i; var y = 0; repeat(9) { y = (y shl 1) or (x and 1); x = x shr 1 }; r[i] = y } }

    /** The log-mel frames of [audio]: one row of 64 values for each hop, row-major, and the number of frames. */
    fun logMel(audio: FloatArray): Pair<FloatArray, Int> {
        if (audio.size < WINDOW) return FloatArray(0) to 0
        val frames = 1 + (audio.size - WINDOW) / HOP
        val out = FloatArray(frames * BANDS)
        val re = DoubleArray(FFT); val im = DoubleArray(FFT); val magnitude = DoubleArray(BINS)
        for (f in 0 until frames) {
            val start = f * HOP
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            // The 400 samples are zero-padded to 512 before the transform.
            for (i in 0 until WINDOW) re[bitReverse[i]] = audio[start + i] * window[i]
            fft(re, im)
            for (k in 0 until BINS) magnitude[k] = sqrt(re[k] * re[k] + im[k] * im[k])
            val row = f * BANDS
            for (b in 0 until BANDS) {
                var sum = 0.0
                for (k in 0 until BINS) { val w = filterbank[k * BANDS + b]; if (w != 0f) sum += magnitude[k] * w }
                out[row + b] = ln(sum + 0.001).toFloat()
            }
        }
        return out to frames
    }

    /** The number of patches in a song of [frames] log-mel frames. */
    fun patchCount(frames: Int): Int = if (frames < PATCH) 0 else 1 + (frames - PATCH) / PATCH_HOP

    /** Patch [index] of a log-mel series: 96 frames of 64 values, row-major. */
    fun patch(logMel: FloatArray, index: Int): FloatArray = logMel.copyOfRange(index * PATCH_HOP * BANDS, (index * PATCH_HOP + PATCH) * BANDS)

    private fun fft(re: DoubleArray, im: DoubleArray) {
        var size = 2
        while (size <= FFT) {
            val half = size / 2; val step = FFT / size
            var start = 0
            while (start < FFT) {
                for (j in 0 until half) {
                    val c = cosTable[j * step]; val s = sinTable[j * step]
                    val a = start + j; val b = a + half
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
        const val SAMPLE_RATE = 16_000
        const val WINDOW = 400
        const val HOP = 160
        const val FFT = 512
        const val BINS = FFT / 2 + 1
        const val BANDS = 64
        const val PATCH = 96
        const val PATCH_HOP = 48
    }
}

/** What the genre model concluded about a song: the family, how sure it is (the song-wide score of its best class, 0 to 1), and the score of every family. */
class GenreVerdict(val family: GenreFamily?, val confidence: Float, val scores: Map<GenreFamily, Float>)

/**
 * Turns the 521 sound scores of YAMNet into a style family. Each AudioSet class that names a style belongs to one family. The scores of each class are averaged
 * over the whole song, the score of a family is that of its best class (a family with many classes does not win for that reason), and the best family wins
 * when it scores at least [MIN_SCORE] and leads the second by [MIN_LEAD].
 */
object YamnetGenres {
    const val MIN_SCORE = 0.06f
    const val MIN_LEAD = 0.015f

    /** The classes of AudioSet that name a style, by display name. Anything else is not a style and is not read. */
    private val CLASSES: Map<String, GenreFamily> = buildMap {
        for (n in listOf("Pop music")) put(n, GenreFamily.POP)
        for (n in listOf("Hip hop music", "Rhythm and blues")) put(n, GenreFamily.HIPHOP)
        for (n in listOf("Rock music", "Heavy metal", "Punk rock", "Grunge", "Progressive rock", "Rock and roll", "Psychedelic rock", "Ska")) put(n, GenreFamily.ROCK)
        for (n in listOf("Soul music", "Funk", "Disco", "Swing music", "Jazz", "Blues", "Gospel music")) put(n, GenreFamily.SOUL)
        for (n in listOf("Reggae", "Salsa music", "Music of Latin America", "Flamenco", "Afrobeat", "Music of Africa")) put(n, GenreFamily.LATIN)
        for (n in listOf("Electronic music", "House music", "Techno", "Electronica", "Electronic dance music", "Trance music", "Dance music")) put(n, GenreFamily.ELECTRONIC)
        for (n in listOf("Dubstep", "Drum and bass")) put(n, GenreFamily.BASS)
        for (n in listOf("Ambient music", "Classical music", "Opera", "Folk music", "Country", "Bluegrass", "New-age music")) put(n, GenreFamily.CHILL)
    }

    /** For each of the classes of [names] (in the order of the network's output), its family, or null. */
    fun table(names: List<String>): Array<GenreFamily?> = Array(names.size) { CLASSES[names[it]] }

    /** The verdict for the mean scores [mean] of a song's patches, with [table] from [table]. */
    fun decide(mean: FloatArray, table: Array<GenreFamily?>): GenreVerdict {
        val best = HashMap<GenreFamily, Float>()
        for (i in mean.indices) { val f = table.getOrNull(i) ?: continue; if (mean[i] > (best[f] ?: 0f)) best[f] = mean[i] }
        val ranked = best.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull() ?: return GenreVerdict(null, 0f, best)
        val second = ranked.getOrNull(1)?.value ?: 0f
        val sure = top.value >= MIN_SCORE && top.value - second >= MIN_LEAD
        return GenreVerdict(if (sure) top.key else null, top.value, best)
    }
}
