package io.github.lnrdo.mytidal.playback.dj.neural

import android.content.Context
import io.github.lnrdo.mytidal.core.dj.MusicGenre
import io.github.lnrdo.mytidal.core.dj.neural.GenreExample
import io.github.lnrdo.mytidal.core.dj.neural.GenreHead
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The genre of a song that someone said it is, and whether that was the listener (who wins) or a catalogue. */
class GenreLabel(val genre: MusicGenre, val fromListener: Boolean)

/**
 * What D'JAI knows about the genres of the songs: the embedding that YAMNet made of each song it listened to, the labels (the listener's corrections and the
 * genres that a catalogue gave), and the head ([GenreHead]) that learns from the labelled songs to name the genre of the others. Everything is kept in the app's
 * folder, and the labels and the head are exported and imported as JSON.
 */
object GenreStore {
    private const val EMBEDDINGS = "djai_genre_embeddings.bin"
    private const val LABELS = "djai_genre_labels.tsv"
    private const val HEAD = "djai_genre_head.json"
    private const val MAX_EMBEDDINGS = 2000

    private lateinit var dir: File
    private val embeddings = LinkedHashMap<String, FloatArray>()
    private val labels = HashMap<String, GenreLabel>()
    @Volatile private var head = GenreHead()

    /** Told when a label changes, so that D'JAI keeps the genre with the song. Set by the engine. */
    @Volatile var onLabel: ((String, MusicGenre) -> Unit)? = null

    private val mutableStats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = mutableStats.asStateFlow()

    /** The songs listened to, the labels, and whether the head is ready to name genres. */
    data class Stats(val songs: Int = 0, val labels: Int = 0, val corrections: Int = 0, val ready: Boolean = false)

    @Synchronized
    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = context.filesDir
        runCatching {
            File(dir, EMBEDDINGS).takeIf(File::exists)?.inputStream()?.buffered()?.let { DataInputStream(it) }?.use { input ->
                repeat(input.readInt()) { embeddings[input.readUTF()] = FloatArray(input.readInt()) { input.readFloat() } }
            }
        }
        runCatching {
            File(dir, LABELS).takeIf(File::exists)?.readLines()?.forEach { line ->
                val f = line.split('\t')
                val genre = f.getOrNull(1)?.let { n -> MusicGenre.entries.firstOrNull { it.name == n } } ?: return@forEach
                labels[f[0]] = GenreLabel(genre, f.getOrNull(2) == "1")
            }
        }
        runCatching { File(dir, HEAD).takeIf(File::exists)?.readText()?.let { GenreHead.fromJson(it) }?.let { head = it } }
        publish()
    }

    private fun publish() {
        mutableStats.value = Stats(embeddings.size, labels.size, labels.values.count { it.fromListener }, head.ready)
    }

    @Synchronized
    private fun save() {
        if (!::dir.isInitialized) return
        runCatching {
            DataOutputStream(File(dir, EMBEDDINGS).outputStream().buffered()).use { out ->
                out.writeInt(embeddings.size)
                for ((id, e) in embeddings) { out.writeUTF(id); out.writeInt(e.size); for (v in e) out.writeFloat(v) }
            }
            File(dir, LABELS).writeText(labels.entries.joinToString("\n") { (id, l) -> listOf(id.replace('\t', ' '), l.genre.name, if (l.fromListener) "1" else "0").joinToString("\t") })
            File(dir, HEAD).writeText(head.toJson())
        }
    }

    private fun examples(): List<GenreExample> = labels.mapNotNull { (id, l) -> embeddings[id]?.let { GenreExample(id, it, l.genre, l.fromListener) } }

    /** Keeps the embedding of a song that the genre model listened to. */
    @Synchronized
    fun record(trackId: String, embedding: FloatArray) {
        embeddings.remove(trackId)
        embeddings[trackId] = embedding
        while (embeddings.size > MAX_EMBEDDINGS) { val oldest = embeddings.keys.first(); if (oldest in labels) { embeddings.remove(oldest); embeddings[oldest] = embeddings.getOrDefault(oldest, embedding) ; break }; embeddings.remove(oldest) }
        // A song that was labelled before it was listened to joins the training now.
        if (trackId in labels) learn(warm = true) else { save(); publish() }
    }

    /** The label of a song, from the listener or a catalogue. */
    fun labelOf(trackId: String): GenreLabel? = labels[trackId]

    /** A catalogue gave the genre of a song. It never replaces what the listener said. */
    @Synchronized
    fun fromCatalogue(trackId: String, genre: MusicGenre) {
        if (labels[trackId]?.fromListener == true) return
        if (labels[trackId]?.genre == genre) return
        labels[trackId] = GenreLabel(genre, false)
        if (trackId in embeddings) learn(warm = true) else { save(); publish() }
    }

    /** The listener says what the genre of a song is. It wins over everything, and the head learns from it. */
    @Synchronized
    fun fromListener(trackId: String, genre: MusicGenre) {
        labels[trackId] = GenreLabel(genre, true)
        onLabel?.invoke(trackId, genre)
        if (trackId in embeddings) learn(warm = true) else { save(); publish() }
    }

    /** The listener takes a correction back: the song has no label, and the genre model's own reading is used again. */
    @Synchronized
    fun forgetLabel(trackId: String) {
        if (labels.remove(trackId) != null) learn(warm = false)
    }

    private fun learn(warm: Boolean) {
        head.train(examples(), warm = warm)
        save(); publish()
    }

    /** The genre for a song: its label, else what the head says of its embedding, else null (the caller then uses the vote of the genre model). */
    fun predict(trackId: String): MusicGenre? {
        labels[trackId]?.let { return it.genre }
        return embeddings[trackId]?.let { head.predict(it)?.first }
    }

    /** The labels, the embeddings of the labelled songs and the head, as JSON, for export. */
    @Synchronized
    fun export(): String = buildString {
        append("{\"format\":\"opentune-genres\",\"version\":1,\"head\":").append(head.toJson()).append(",\"labels\":[")
        append(labels.entries.joinToString(",") { (id, l) ->
            val e = embeddings[id]
            "{\"id\":\"${id.replace("\\", "\\\\").replace("\"", "\\\"")}\",\"genre\":\"${l.genre.name}\",\"listener\":${l.fromListener},\"embedding\":" +
                (e?.joinToString(",", "[", "]") { "%.4f".format(java.util.Locale.ROOT, it) } ?: "null") + "}"
        })
        append("]}")
    }

    /** Reads a file that [export] wrote: its labels (and the embeddings that came with them) are merged with the ones here, and the head learns again. Returns the number read. */
    @Synchronized
    fun import(json: String): Int {
        var read = 0
        for (m in Regex("\\{\"id\":\"((?:\\\\.|[^\"\\\\])*)\",\"genre\":\"([A-Z_]+)\",\"listener\":(true|false),\"embedding\":(null|\\[[^\\]]*])}").findAll(json)) {
            val id = m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
            val genre = MusicGenre.entries.firstOrNull { it.name == m.groupValues[2] } ?: continue
            labels[id] = GenreLabel(genre, m.groupValues[3] == "true")
            m.groupValues[4].takeIf { it != "null" }?.let { list -> embeddings.getOrPut(id) { list.trim('[', ']').split(',').map { it.trim().toFloat() }.toFloatArray() } }
            read++
        }
        if (read > 0) learn(warm = false)
        return read
    }

    /** Forgets every label and everything learned. The embeddings of the songs stay. */
    @Synchronized
    fun forgetAll() {
        labels.clear(); head = GenreHead()
        save(); publish()
    }
}
