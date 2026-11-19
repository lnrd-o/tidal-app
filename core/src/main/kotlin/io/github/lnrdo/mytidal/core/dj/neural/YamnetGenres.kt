package io.github.lnrdo.mytidal.core.dj.neural

import io.github.lnrdo.mytidal.core.dj.MusicGenre
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

/** What the genre model concluded about a song: the genre, how sure it is (the song-wide score of its best class, 0 to 1), the score of every genre, and its embedding. */
class GenreVerdict(val genre: MusicGenre?, val confidence: Float, val scores: Map<MusicGenre, Float>, val embedding: FloatArray? = null)

/**
 * Turns the 521 sound scores of YAMNet into a genre. Each AudioSet class that names a style belongs to one genre. The scores of each class are averaged over
 * the whole song, the score of a genre is that of its best class, and the best genre wins when it scores at least [MIN_SCORE] and leads the second by
 * [MIN_LEAD]. Many genres have no class of their own in AudioSet (reggaeton, trap, indie...): those are learned by [GenreHead] from the listener's answers.
 */
object YamnetGenres {
    const val MIN_SCORE = 0.06f
    const val MIN_LEAD = 0.015f

    /** The classes of AudioSet that name a genre, by display name. Anything else is not a genre and is not read. */
    private val CLASSES: Map<String, MusicGenre> = buildMap {
        put("Pop music", MusicGenre.POP)
        put("Hip hop music", MusicGenre.HIPHOP); put("Rhythm and blues", MusicGenre.RNB)
        for (n in listOf("Rock music", "Grunge", "Progressive rock", "Rock and roll", "Psychedelic rock")) put(n, MusicGenre.ROCK)
        put("Punk rock", MusicGenre.PUNK); put("Ska", MusicGenre.PUNK)
        put("Heavy metal", MusicGenre.METAL)
        for (n in listOf("Soul music", "Gospel music")) put(n, MusicGenre.SOUL)
        put("Funk", MusicGenre.FUNK); put("Disco", MusicGenre.DISCO)
        put("Jazz", MusicGenre.JAZZ); put("Swing music", MusicGenre.JAZZ); put("Blues", MusicGenre.BLUES)
        put("Country", MusicGenre.COUNTRY); put("Bluegrass", MusicGenre.COUNTRY); put("Folk music", MusicGenre.FOLK)
        put("Reggae", MusicGenre.REGGAE); put("Salsa music", MusicGenre.TROPICAL)
        put("Music of Latin America", MusicGenre.LATIN); put("Flamenco", MusicGenre.LATIN)
        put("Afrobeat", MusicGenre.AFROBEATS); put("Music of Africa", MusicGenre.AFROBEATS)
        put("House music", MusicGenre.HOUSE); put("Techno", MusicGenre.TECHNO); put("Trance music", MusicGenre.TRANCE)
        for (n in listOf("Electronic music", "Electronica", "Electronic dance music", "Dance music")) put(n, MusicGenre.EDM)
        put("Dubstep", MusicGenre.DUBSTEP); put("Drum and bass", MusicGenre.DNB)
        for (n in listOf("Ambient music", "New-age music")) put(n, MusicGenre.AMBIENT)
        for (n in listOf("Classical music", "Opera")) put(n, MusicGenre.CLASSICAL)
    }

    /** For each of the classes of [names] (in the order of the network's output), its genre, or null. */
    fun table(names: List<String>): Array<MusicGenre?> = Array(names.size) { CLASSES[names[it]] }

    /** The verdict for the mean scores [mean] of a song's patches, with [table] from [table]. */
    fun decide(mean: FloatArray, table: Array<MusicGenre?>, embedding: FloatArray? = null): GenreVerdict {
        val best = HashMap<MusicGenre, Float>()
        for (i in mean.indices) { val g = table.getOrNull(i) ?: continue; if (mean[i] > (best[g] ?: 0f)) best[g] = mean[i] }
        val ranked = best.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull() ?: return GenreVerdict(null, 0f, best, embedding)
        val second = ranked.getOrNull(1)?.value ?: 0f
        val sure = top.value >= MIN_SCORE && top.value - second >= MIN_LEAD
        return GenreVerdict(if (sure) top.key else null, top.value, best, embedding)
    }
}
