package io.github.yyan115.opentune.playback.dj.neural

import android.content.Context
import io.github.yyan115.opentune.core.dj.neural.NeuralBeats
import io.github.yyan115.opentune.core.dj.neural.RhythmAnswer
import io.github.yyan115.opentune.core.dj.neural.RhythmCalibration
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the rhythm model found in a song, and what the listener said about it. Times are in milliseconds of the song. */
data class RhythmEntry(
    val trackId: String,
    val neuralBpm: Double,
    val firstDownbeatMs: Long?,
    val beatsPerBar: Int,
    val beats: Int,
    val accelerator: String,
    val dspBpm: Double? = null,
    val catalogueBpm: Double? = null,
    val swing: Double? = null,
    val energy: Double? = null,
    /** The tempo the listener confirmed or tapped, or null while not asked or not answered. */
    val confirmedBpm: Double? = null,
)

/**
 * Keeps the rhythm model's findings for each song and the listener's answers, and the trained calibration ([RhythmCalibration]), in two files in the app's
 * own folder. The tempo that D'JAI uses for a song comes from here first: the listener's answer, else the calibrated reading of the network.
 */
object RhythmStore {
    private const val ENTRIES = "djai_rhythm_songs.tsv"
    private const val MODEL = "djai_rhythm_model.json"
    private lateinit var dir: File
    private val entries = LinkedHashMap<String, RhythmEntry>()
    @Volatile var calibration = RhythmCalibration()
        private set

    private val mutable = MutableStateFlow<Map<String, RhythmEntry>>(emptyMap())
    val songs: StateFlow<Map<String, RhythmEntry>> = mutable.asStateFlow()
    /** The number of answers the calibration learned from, and how many of them it reads right now. */
    private val mutableStats = MutableStateFlow(0 to 0.0)
    val stats: StateFlow<Pair<Int, Double>> = mutableStats.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = context.filesDir
        runCatching {
            File(dir, ENTRIES).takeIf(File::exists)?.readLines()?.forEach { line ->
                val f = line.split('\t')
                if (f.size < 11) return@forEach
                fun d(i: Int) = f[i].toDoubleOrNull()
                entries[f[0]] = RhythmEntry(f[0], f[1].toDouble(), f[2].toLongOrNull(), f[3].toInt(), f[4].toInt(), f[5], d(6), d(7), d(8), d(9), d(10))
            }
        }
        runCatching { File(dir, MODEL).takeIf(File::exists)?.readText()?.let { calibration = RhythmCalibration.fromJson(it) } }
        publish()
    }

    private fun publish() {
        mutable.value = HashMap(entries)
        mutableStats.value = calibration.answers.size to calibration.accuracy()
    }

    @Synchronized
    private fun save() {
        if (!::dir.isInitialized) return
        runCatching {
            File(dir, ENTRIES).writeText(entries.values.joinToString("\n") { e ->
                listOf(e.trackId.replace('\t', ' '), e.neuralBpm, e.firstDownbeatMs ?: "-", e.beatsPerBar, e.beats, e.accelerator, e.dspBpm ?: "-", e.catalogueBpm ?: "-", e.swing ?: "-", e.energy ?: "-", e.confirmedBpm ?: "-").joinToString("\t")
            })
            File(dir, MODEL).writeText(calibration.toJson())
        }
    }

    /** Keeps what the network found in a song. An answer that the listener gave before is kept. */
    @Synchronized
    fun record(trackId: String, found: NeuralBeats, startMs: Long, accelerator: String, dspBpm: Double?, catalogueBpm: Double?, swing: Double?, energy: Double?) {
        val bpm = found.bpm ?: return
        val old = entries[trackId]
        entries[trackId] = RhythmEntry(trackId, bpm, found.firstDownbeat?.let { (it * 1000).toLong() + startMs }, found.beatsPerBar, found.beats.size, accelerator,
            dspBpm, catalogueBpm, swing, energy, old?.confirmedBpm)
        save(); publish()
    }

    /** The listener confirmed or corrected the tempo of a song: it is kept, and the calibration learns from it. */
    @Synchronized
    fun answer(trackId: String, bpm: Double) {
        val e = entries[trackId] ?: return
        entries[trackId] = e.copy(confirmedBpm = bpm)
        calibration.learn(RhythmAnswer(trackId, e.neuralBpm, bpm, e.dspBpm, e.catalogueBpm, e.swing, e.energy))
        save(); publish()
    }

    /** The tempo D'JAI should use for a song: the listener's, else the calibrated network's, else null. */
    fun bpmFor(trackId: String): Double? {
        val e = entries[trackId] ?: return null
        return e.confirmedBpm ?: calibration.predict(e.neuralBpm, e.dspBpm, e.catalogueBpm, e.swing, e.energy)
    }

    /** The trained model and its answers as one JSON file, for export. */
    fun export(): String = calibration.toJson()

    /** Reads an exported file: its answers are merged with the ones here (the newer file wins for a song), and the model learns again from all of them. */
    @Synchronized
    fun import(json: String): Int {
        val incoming = RhythmCalibration.fromJson(json)
        val merged = RhythmCalibration()
        for (a in calibration.answers) merged.answers.add(a)
        for (a in incoming.answers) { merged.answers.removeAll { it.trackId == a.trackId }; merged.answers.add(a) }
        merged.train()
        calibration = merged
        save(); publish()
        return incoming.answers.size
    }

    /** Forgets the answers and the training. What the network found in each song stays. */
    @Synchronized
    fun forgetAnswers() {
        calibration = RhythmCalibration()
        for ((id, e) in entries.toMap()) entries[id] = e.copy(confirmedBpm = null)
        save(); publish()
    }
}
