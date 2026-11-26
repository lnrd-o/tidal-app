package io.github.lnrdo.mytidal.core.dj.neural

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Brings mono audio to 22,050 Hz as it arrives, with the same windowed-sinc filter as [Resampler], and keeps the result as 16-bit samples: a 5-minute song
 * takes 13 MB instead of 50 MB of floats at 44.1 kHz. The network needs nothing finer than 16 bits.
 */
class StreamingResampler(private val from: Int, private val to: Int = LogMel.SAMPLE_RATE) {
    private val kernel = SincKernel(from, to)
    private var input = FloatArray(1 shl 16)
    private var inputStart = 0L // the absolute index of input[0]
    private var inputCount = 0
    private var nextOut = 0L
    private var out = ShortArray(1 shl 20)
    var outCount = 0
        private set
    private var finished = false

    /**
     * Adds [n] samples. The analyzer feeds it from its own thread while another thread may end it and read the result, so every call holds the lock;
     * samples that arrive after the end are dropped.
     */
    @Synchronized
    fun feed(samples: FloatArray, n: Int) {
        if (finished) return
        if (inputCount + n > input.size) input = input.copyOf(max(input.size * 2, inputCount + n))
        System.arraycopy(samples, 0, input, inputCount, n); inputCount += n
        produce(final = false)
    }

    /** Ends the song: the last samples are made with what there is. Later calls do nothing. */
    @Synchronized
    fun finish() { if (!finished) { finished = true; produce(final = true) } }

    private fun produce(final: Boolean) {
        val available = inputStart + inputCount
        val half = kernel.half
        while (true) {
            val centre = kernel.centreOf(nextOut)
            if (!final && centre + half >= available) break
            if (final && centre >= available) break
            val row = kernel.weights[kernel.phaseOf(nextOut)]
            val first = max(inputStart, centre - half); val last = min(available - 1, centre + half)
            var sum = 0.0; var weights = 0.0
            var i = first
            while (i <= last) {
                val h = row[(i - centre + half).toInt()]
                sum += input[(i - inputStart).toInt()] * h; weights += h
                i++
            }
            val v = if (weights != 0.0) sum / weights else 0.0
            if (outCount == out.size) out = out.copyOf(out.size * 2)
            out[outCount++] = (v * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
            nextOut++
        }
        // What no later output can reach is dropped.
        val keepFrom = max(inputStart, kernel.centreOf(nextOut) - half - 1)
        val drop = (keepFrom - inputStart).toInt()
        if (drop > 0 && drop <= inputCount) {
            System.arraycopy(input, drop, input, 0, inputCount - drop)
            inputCount -= drop; inputStart += drop
        }
    }

    /** The audio at the new rate, as floats. */
    @Synchronized
    fun result(): FloatArray = FloatArray(outCount) { out[it] / 32768f }

    /** Copies the samples made so far from [from] until [end] into [dest], as they are (16-bit); for a reader that takes them while they are made. */
    @Synchronized
    fun copyOut(from: Int, end: Int, dest: ShortArray) { System.arraycopy(out, from, dest, 0, end - from) }
}

/** One answer from the listener: what the network said, and the tempo that the listener confirmed or corrected to. */
data class RhythmAnswer(
    val trackId: String,
    val neuralBpm: Double,
    val confirmedBpm: Double,
    /** What the hand-built detector and a catalogue said, when they said anything. */
    val dspBpm: Double? = null,
    val catalogueBpm: Double? = null,
    val swing: Double? = null,
    val energy: Double? = null,
)

/**
 * The part of the rhythm model that the listener trains. The network finds the beats well but, like every tracker, can count a song at half or double its
 * felt tempo, or in threes. Given the network's tempo, this model scores five readings (×½, ×⅔, ×1, ×1.5, ×2) from what is known about the song, and learns
 * from the listener's answers which one people mean, by logistic regression. It starts out trusting the network, and every confirmation or correction
 * moves it. Its weights and the answers it learned from are saved and exported as JSON.
 */
class RhythmCalibration(
    private val weights: DoubleArray = DEFAULT.copyOf(),
    val answers: MutableList<RhythmAnswer> = ArrayList(),
) {
    /** The tempo the listener most likely means, for the network's [neuralBpm] and what else is known. */
    fun predict(neuralBpm: Double, dspBpm: Double? = null, catalogueBpm: Double? = null, swing: Double? = null, energy: Double? = null): Double {
        val scores = FACTORS.indices.map { score(features(it, neuralBpm, dspBpm, catalogueBpm, swing, energy)) }
        return neuralBpm * FACTORS[scores.indices.maxByOrNull { scores[it] }!!]
    }

    /** How sure the model is of its reading, from 0 to 1. */
    fun confidence(neuralBpm: Double, dspBpm: Double? = null, catalogueBpm: Double? = null, swing: Double? = null, energy: Double? = null): Double {
        val scores = FACTORS.indices.map { score(features(it, neuralBpm, dspBpm, catalogueBpm, swing, energy)) }
        val top = scores.max()
        val sum = scores.sumOf { exp(it - top) }
        return 1.0 / sum
    }

    /** Learns from a new answer (and keeps it, replacing an older answer for the same song). Returns the reading the answer stands for, or null. */
    fun learn(answer: RhythmAnswer): Int? {
        answers.removeAll { it.trackId == answer.trackId }
        answers.add(answer)
        train()
        return target(answer)
    }

    /** Learns again from all the answers, from the starting weights. */
    fun train(epochs: Int = 200, rate: Double = 0.1) {
        DEFAULT.copyInto(weights)
        val usable = answers.mapNotNull { a -> target(a)?.let { a to it } }
        if (usable.isEmpty()) return
        repeat(epochs) {
            for ((a, t) in usable) {
                val f = FACTORS.indices.map { features(it, a.neuralBpm, a.dspBpm, a.catalogueBpm, a.swing, a.energy) }
                val s = f.map(::score)
                val top = s.max()
                val p = s.map { exp(it - top) }.let { e -> val z = e.sum(); e.map { it / z } }
                // The gradient of the cross-entropy, and a small pull back toward the starting weights so that a few answers do not swing it too far.
                for (k in FACTORS.indices) {
                    val g = p[k] - (if (k == t) 1.0 else 0.0)
                    for (j in weights.indices) weights[j] -= rate * (g * f[k][j] + 0.01 * (weights[j] - DEFAULT[j]) / FACTORS.size)
                }
            }
        }
    }

    /** The share of the answers that the model reads right now. */
    fun accuracy(): Double {
        val usable = answers.mapNotNull { a -> target(a)?.let { a to it } }
        if (usable.isEmpty()) return 0.0
        return usable.count { (a, t) -> abs(predict(a.neuralBpm, a.dspBpm, a.catalogueBpm, a.swing, a.energy) / (a.neuralBpm * FACTORS[t]) - 1) < 0.01 }.toDouble() / usable.size
    }

    private fun score(f: DoubleArray): Double { var s = 0.0; for (j in f.indices) s += weights[j] * f[j]; return s }

    private fun features(k: Int, neural: Double, dsp: Double?, catalogue: Double?, swing: Double?, energy: Double?): DoubleArray {
        val bpm = neural * FACTORS[k]
        val f = DoubleArray(SIZE)
        f[k] = 1.0 // which reading
        val distance = ln(bpm / 120.0) / ln(2.0)
        f[5] = distance * distance // how far from the middle of dance tempos, in octaves, squared
        f[6] = if (dsp != null && near(bpm, dsp)) 1.0 else 0.0
        f[7] = if (catalogue != null && near(bpm, catalogue)) 1.0 else 0.0
        f[8] = if (bpm < 90) (swing ?: 0.5) - 0.5 else 0.0 // a swung groove is often felt slow
        f[9] = if (bpm > 150) ((energy ?: 5.0) - 5.0) / 5.0 else 0.0 // a fast reading fits a loud song
        f[10] = if (bpm < 85) ((energy ?: 5.0) - 5.0) / 5.0 else 0.0
        return f
    }

    private fun near(a: Double, b: Double) = abs(a / b - 1) < 0.04

    /** Which reading the listener's tempo is, or null when it is none of them (the network missed the beat entirely). */
    private fun target(a: RhythmAnswer): Int? = FACTORS.indices.firstOrNull { near(a.neuralBpm * FACTORS[it], a.confirmedBpm) }

    fun toJson(): String = buildString {
        append("{\"format\":\"opentune-rhythm\",\"version\":1,\"weights\":[")
        append(weights.joinToString(","))
        append("],\"answers\":[")
        append(answers.joinToString(",") { a ->
            "{\"id\":${quote(a.trackId)},\"neural\":${a.neuralBpm},\"confirmed\":${a.confirmedBpm},\"dsp\":${a.dspBpm ?: "null"},\"catalogue\":${a.catalogueBpm ?: "null"},\"swing\":${a.swing ?: "null"},\"energy\":${a.energy ?: "null"}}"
        })
        append("]}")
    }

    companion object {
        val FACTORS = doubleArrayOf(0.5, 2.0 / 3.0, 1.0, 1.5, 2.0)
        private const val SIZE = 11
        /** It starts out trusting the network's own reading, a little against far tempos and toward what other sources agree with. */
        private val DEFAULT = doubleArrayOf(-2.0, -3.0, 2.0, -3.0, -2.0, -0.5, 1.0, 1.0, 0.0, 0.0, 0.0)

        private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /** Reads what [toJson] wrote. Answers are kept; the weights are learned again from them, so a file from an older version still works. */
        fun fromJson(text: String): RhythmCalibration {
            val answers = ArrayList<RhythmAnswer>()
            val block = Regex("\"answers\"\\s*:\\s*\\[(.*)]\\s*}\\s*$", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1).orEmpty()
            for (m in Regex("\\{([^{}]*)}").findAll(block)) {
                val body = m.groupValues[1]
                fun num(name: String) = Regex("\"$name\"\\s*:\\s*(-?[0-9.eE+-]+|null)").find(body)?.groupValues?.get(1)?.toDoubleOrNull()
                val id = Regex("\"id\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(body)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\") ?: continue
                val neural = num("neural") ?: continue; val confirmed = num("confirmed") ?: continue
                answers.add(RhythmAnswer(id, neural, confirmed, num("dsp"), num("catalogue"), num("swing"), num("energy")))
            }
            return RhythmCalibration(DEFAULT.copyOf(), answers).also { it.train() }
        }
    }
}
