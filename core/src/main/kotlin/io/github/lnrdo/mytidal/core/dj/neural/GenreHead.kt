package io.github.lnrdo.mytidal.core.dj.neural

import io.github.lnrdo.mytidal.core.dj.MusicGenre
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** A song as the genre head sees it: the 1024-number embedding that YAMNet makes of it (its mean over the song), and the style that someone said it is. */
class GenreExample(val trackId: String, val embedding: FloatArray, val genre: MusicGenre, val fromListener: Boolean)

/**
 * The part of the genre model that learns. YAMNet's scores for the 521 sounds of AudioSet say little about style in a way that fits a listener's own
 * library, so on top of its 1024-number embedding of each song sits a small classifier (softmax regression, one weight vector for each genre). It learns from the styles that are known for songs: the ones that the listener corrected, and the ones that a catalogue gave. Until it has
 * seen enough of them it says nothing, and the vote of the raw scores is used. Its weights are saved and exported as JSON.
 */
class GenreHead(private val dim: Int = 1024) {
    private val families = MusicGenre.entries
    private val weights = Array(families.size) { DoubleArray(dim + 1) }
    /** The number of examples that it learned from, and how many different families they had. */
    var examples = 0
        private set
    var classes = 0
        private set

    /** True when it has seen enough to be believed: [MIN_EXAMPLES] songs of at least [MIN_CLASSES] families. */
    val ready: Boolean get() = examples >= MIN_EXAMPLES && classes >= MIN_CLASSES

    private fun scaled(e: FloatArray): DoubleArray {
        var norm = 0.0
        for (v in e) norm += v.toDouble() * v
        val k = SCALE / max(sqrt(norm), 1e-6)
        return DoubleArray(dim + 1) { if (it < dim) e[it] * k else 1.0 }
    }

    private fun probabilities(x: DoubleArray): DoubleArray {
        val scores = DoubleArray(families.size) { f -> var s = 0.0; val w = weights[f]; for (j in x.indices) s += w[j] * x[j]; s }
        val top = scores.max()
        val e = DoubleArray(scores.size) { exp(scores[it] - top) }
        val z = e.sum()
        return DoubleArray(e.size) { e[it] / z }
    }

    /** The style for a song's embedding and how sure the head is of it, or null when it is not ready or not sure. */
    fun predict(embedding: FloatArray): Pair<MusicGenre, Double>? {
        if (!ready || embedding.size != dim) return null
        val p = probabilities(scaled(embedding))
        val best = p.indices.maxByOrNull { p[it] }!!
        return if (p[best] >= MIN_PROBABILITY) families[best] to p[best] else null
    }

    /**
     * Learns from [data]. With [warm] the weights it has are kept and trained a little more, which is how a new answer is taken in; otherwise it starts again.
     * Examples of the listener count double.
     */
    fun train(data: List<GenreExample>, warm: Boolean = false, epochs: Int = if (warm) 40 else 150, rate: Double = 0.05, decay: Double = 0.002) {
        val usable = data.filter { it.embedding.size == dim }
        examples = usable.size; classes = usable.map { it.genre }.distinct().size
        if (!warm) for (w in weights) java.util.Arrays.fill(w, 0.0)
        if (usable.isEmpty()) return
        val xs = usable.map { scaled(it.embedding) }
        val order = usable.indices.toMutableList()
        val random = java.util.Random(11)
        repeat(epochs) {
            order.shuffle(random)
            for (i in order) {
                val p = probabilities(xs[i])
                val target = families.indexOf(usable[i].genre)
                val weight = if (usable[i].fromListener) 2.0 else 1.0
                for (f in families.indices) {
                    val g = (p[f] - (if (f == target) 1.0 else 0.0)) * weight
                    val w = weights[f]; val x = xs[i]
                    for (j in x.indices) w[j] -= rate * (g * x[j] + decay * w[j])
                }
            }
        }
    }

    fun toJson(): String = buildString {
        append("{\"format\":\"opentune-genre\",\"version\":1,\"dim\":$dim,\"examples\":$examples,\"classes\":$classes,\"weights\":[")
        append(weights.joinToString(",") { row -> row.joinToString(",", "[", "]") { "%.5f".format(java.util.Locale.ROOT, it) } })
        append("]}")
    }

    companion object {
        const val MIN_EXAMPLES = 20
        const val MIN_CLASSES = 3
        const val MIN_PROBABILITY = 0.45
        /** The embeddings are brought to this length, so that the weights do not depend on how loud a song is. */
        private const val SCALE = 8.0

        fun fromJson(text: String, dim: Int = 1024): GenreHead? {
            val head = GenreHead(dim)
            val rows = Regex("\\[([^\\[\\]]*)]").findAll(text.substringAfter("\"weights\":")).map { m -> m.groupValues[1].split(',').map { it.trim().toDouble() } }.toList()
            if (rows.size != head.families.size || rows.any { it.size != dim + 1 }) return null
            for ((f, row) in rows.withIndex()) for (j in row.indices) head.weights[f][j] = row[j]
            head.examples = Regex("\"examples\":(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
            head.classes = Regex("\"classes\":(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
            return head
        }
    }
}
