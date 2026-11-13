package io.github.yyan115.opentune.core.dj

import io.github.yyan115.opentune.core.Spectrum
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The Camelot wheel: a number from 1 to 12 and a letter, A for minor keys and B for major keys. */
object Camelot {
    // Indexed by pitch class, C = 0.
    private val major = arrayOf("8B", "3B", "10B", "5B", "12B", "7B", "2B", "9B", "4B", "11B", "6B", "1B")
    private val minor = arrayOf("5A", "12A", "7A", "2A", "9A", "4A", "11A", "6A", "1A", "8A", "3A", "10A")

    /**
     * The Camelot code of a key given by name, as an online catalogue gives it: a note such as "F", "Bb", "FSharp" or "EFlat" and a scale such as
     * "MAJOR" or "MINOR". Returns null when the note is not understood.
     */
    fun fromName(key: String?, scale: String?): String? {
        val text = key?.trim().orEmpty()
        if (text.isEmpty()) return null
        val base = when (text[0].uppercaseChar()) { 'C' -> 0; 'D' -> 2; 'E' -> 4; 'F' -> 5; 'G' -> 7; 'A' -> 9; 'B' -> 11; else -> return null }
        val rest = text.drop(1).lowercase()
        val shift = when { rest.startsWith("#") || rest.startsWith("sharp") -> 1; rest.startsWith("b") || rest.startsWith("flat") -> -1; else -> 0 }
        val minor = scale?.trim()?.lowercase()?.startsWith("min") == true
        return code(base + shift, minor)
    }

    private val majorNames = arrayOf("C", "Db", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private val minorNames = arrayOf("Cm", "C#m", "Dm", "Ebm", "Em", "Fm", "F#m", "Gm", "G#m", "Am", "Bbm", "Bm")

    /** The key that a Camelot code stands for, in musical notation: "8A" is "Am" and "8B" is "C". Null for something that is not a code. */
    fun noteName(code: String?): String? {
        if (code == null || code.length < 2) return null
        val index = (if (code.last() == 'B') major else if (code.last() == 'A') minor else return null).indexOf(code)
        if (index < 0) return null
        return if (code.last() == 'B') majorNames[index] else minorNames[index]
    }

    fun code(pitchClass: Int, isMinor: Boolean): String = (if (isMinor) minor else major)[((pitchClass % 12) + 12) % 12]

    private fun number(code: String) = code.dropLast(1).toIntOrNull()
    private fun letter(code: String) = code.lastOrNull()

    /** How well two keys mix, from 0 to 1. Same key 1, a step on the wheel .9, the relative key .8, two steps .5, a diagonal step .4. */
    fun compatibility(a: String?, b: String?): Double {
        if (a == null || b == null) return 0.5
        val na = number(a) ?: return 0.5; val nb = number(b) ?: return 0.5
        val steps = minOf(abs(na - nb), 12 - abs(na - nb))
        val same = letter(a) == letter(b)
        return when {
            same && steps == 0 -> 1.0
            same && steps == 1 -> 0.9
            !same && steps == 0 -> 0.8
            same && steps == 2 -> 0.5
            !same && steps == 1 -> 0.4
            else -> 0.1
        }
    }
}

class KeyResult(val camelot: String, val pitchClass: Int, val minor: Boolean, val confidence: Float)

/** Finds the key of a piece from its spectrum: the average chroma is compared with the Krumhansl-Schmuckler profiles of the 24 keys. */
class KeyDetector(private val rate: Int) {
    private val chroma = DoubleArray(12)
    var frames = 0
        private set

    // Bins of the 2048-point spectrum between about 220 Hz and 2.5 kHz, where one bin is narrower than a semitone-resolved note.
    private val first = (220.0 * Spectrum.SIZE / rate).toInt().coerceAtLeast(1)
    private val last = (2500.0 * Spectrum.SIZE / rate).toInt().coerceAtMost(Spectrum.SIZE / 2 - 1)
    private val pitchOfBin = IntArray(Spectrum.SIZE / 2) { bin ->
        if (bin < first || bin > last) -1
        else { val f = bin.toDouble() * rate / Spectrum.SIZE; ((12.0 * ln(f / 440.0) / ln(2.0) + 69.0).roundToInt() % 12 + 12) % 12 }
    }

    fun add(power: FloatArray) {
        for (bin in first..last) chroma[pitchOfBin[bin]] += sqrt(power[bin].toDouble())
        frames++
    }

    fun reset() { chroma.fill(0.0); frames = 0 }

    fun result(): KeyResult? {
        if (frames < 20) return null
        val total = chroma.sum()
        if (total <= 0.0) return null
        val normal = DoubleArray(12) { chroma[it] / total }
        var best = -2.0; var second = -2.0; var bestPitch = 0; var bestMinor = false
        for (minor in listOf(false, true)) {
            val profile = if (minor) MINOR else MAJOR
            for (tonic in 0 until 12) {
                val score = correlation(normal, profile, tonic)
                if (score > best) { second = best; best = score; bestPitch = tonic; bestMinor = minor } else if (score > second) second = score
            }
        }
        return KeyResult(Camelot.code(bestPitch, bestMinor), bestPitch, bestMinor, ((best - second) * 4).coerceIn(0.0, 1.0).toFloat())
    }

    private fun correlation(chroma: DoubleArray, profile: DoubleArray, tonic: Int): Double {
        val meanC = chroma.average(); val meanP = profile.average()
        var num = 0.0; var dc = 0.0; var dp = 0.0
        for (i in 0 until 12) {
            val c = chroma[(i + tonic) % 12] - meanC; val p = profile[i] - meanP
            num += c * p; dc += c * c; dp += p * p
        }
        return if (dc == 0.0 || dp == 0.0) 0.0 else num / sqrt(dc * dp)
    }

    private companion object {
        val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
        val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)
    }
}
