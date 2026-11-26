package io.github.lnrdo.mytidal.stems

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import io.github.lnrdo.mytidal.OpenTuneApp
import io.github.lnrdo.mytidal.core.Track
import io.github.lnrdo.mytidal.core.stems.ScnetNetwork
import io.github.lnrdo.mytidal.core.stems.ScnetSeparator
import io.github.lnrdo.mytidal.core.stems.Stem
import io.github.lnrdo.mytidal.core.stems.StemSeparator
import io.github.lnrdo.mytidal.core.stems.StemSink
import io.github.lnrdo.mytidal.playback.dj.neural.Accelerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** The stems of songs, kept on the device: for each song a folder with the left and the right channel of each stem as 16-bit samples at 44.1 kHz. */
class StemStore(private val context: Context) {
    private val root get() = File(context.filesDir, "stems/tracks").apply { mkdirs() }

    private fun key(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    fun folder(id: String) = File(root, key(id))
    fun file(id: String, stem: Stem, channel: Int) = File(folder(id), "${stem.id}_${if (channel == 0) "l" else "r"}.pcm")
    private fun meta(id: String) = File(folder(id), "meta.txt")

    /** The number of samples (per channel) of the song's stems, or 0 when it has none. */
    fun length(id: String): Int = runCatching { meta(id).readLines().firstOrNull { it.startsWith("length=") }?.substringAfter('=')?.toInt() ?: 0 }.getOrDefault(0)
    /** Whether the song's stems are all there: the record, and the eight files with the length that the record gives. */
    fun has(id: String): Boolean {
        val n = length(id)
        return n > 0 && Stem.entries.all { s -> (0..1).all { c -> file(id, s, c).let { f -> f.isFile && f.length() >= n * 2L } } }
    }
    fun model(id: String): String? = runCatching { meta(id).readLines().firstOrNull { it.startsWith("model=") }?.substringAfter('=') }.getOrNull()

    fun delete(id: String) { folder(id).deleteRecursively() }

    fun sizeBytes(): Long = root.walkTopDown().filter(File::isFile).sumOf { it.length() }

    /** Removes the songs used longest ago until the stems take at most [limitBytes]. */
    fun evict(limitBytes: Long, keep: String? = null) {
        val folders = root.listFiles()?.filter { it.isDirectory && it != keep?.let(::folder) }?.sortedBy { f -> (f.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L) } ?: return
        var total = sizeBytes()
        for (f in folders) { if (total <= limitBytes) break; total -= f.walkTopDown().filter(File::isFile).sumOf { it.length() }; f.deleteRecursively() }
    }

    /** Writes the stems of [id] as the separation hands them over. The song counts as separated only when [finish] is called. */
    inner class Writer(private val id: String, private val model: String) : StemSink {
        // The folder is made once, empty: making it again for each file would delete the files that were already open.
        private val streams = folder(id).also { it.deleteRecursively(); it.mkdirs() }.let { _ ->
            Array(4) { s -> Array(2) { c -> BufferedOutputStream(FileOutputStream(file(id, Stem.entries[s], c)), 1 shl 16) } }
        }
        private var length = 0
        private var buffer = ByteArray(1 shl 16)

        override fun write(stem: Int, channel: Int, offset: Int, samples: FloatArray, count: Int) {
            if (buffer.size < count * 2) buffer = ByteArray(count * 2)
            for (i in 0 until count) { val v = (samples[i] * 32767f).coerceIn(-32768f, 32767f).toInt(); buffer[2 * i] = v.toByte(); buffer[2 * i + 1] = (v shr 8).toByte() }
            streams[stem][channel].write(buffer, 0, count * 2)
            length = maxOf(length, offset + count)
        }

        fun finish() { streams.forEach { s -> s.forEach { it.close() } }; meta(id).writeText("length=$length\nmodel=$model\ncreated=${System.currentTimeMillis()}\n") }
        fun abort() { runCatching { streams.forEach { s -> s.forEach { it.close() } } }; delete(id) }
    }
}

/** What a song's separation is doing now. */
data class StemProgress(val phase: Phase, val progress: Float = 0f, val model: StemModel = StemModel.SPLEETER, val ranOn: Accelerator? = null, val seconds: Float = 0f) {
    /** [PREPARING]: the NPU graph of SCNet is built, once, which takes a few seconds. */
    enum class Phase { WAITING, PREPARING, READING, SEPARATING, DONE, FAILED }
}

/**
 * Separates songs one at a time in the background: the song is read at full speed by a hidden player, separated by the chosen network (Spleeter on the NPU,
 * Open-Unmix on the CPU), and its stems are kept in the [store]. The progress of every song is in [state].
 */
@UnstableApi
class StemManager(private val app: OpenTuneApp) {
    val store = StemStore(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val one = Mutex()
    private val jobs = HashMap<String, Job>()
    private val mutable = MutableStateFlow<Map<String, StemProgress>>(emptyMap())
    val state: StateFlow<Map<String, StemProgress>> = mutable.asStateFlow()

    private fun set(id: String, p: StemProgress?) = mutable.update { if (p == null) it - id else it + (id to p) }

    fun separate(track: Track, model: StemModel, limitMb: Int = 1500, power: Int = 2) {
        if (jobs[track.id]?.isActive == true) return
        set(track.id, StemProgress(StemProgress.Phase.WAITING, model = model))
        jobs[track.id] = scope.launch {
            one.withLock {
                val began = System.currentTimeMillis()
                try { coroutineScope {
                    // The first time, SCNet's NPU graph is built before anything else, with its own progress.
                    val prepared: AutoCloseable? = if (model == StemModel.SCNET && StemNetworks.scnetNeedsPreparing(app)) {
                        set(track.id, StemProgress(StemProgress.Phase.PREPARING, 0f, model, Accelerator.NPU))
                        StemNetworks.openScnet(app, Accelerator.NPU) { set(track.id, StemProgress(StemProgress.Phase.PREPARING, it, model, Accelerator.NPU)) }
                    } else null
                    set(track.id, StemProgress(StemProgress.Phase.READING, 0f, model))
                    // The network is opened while the song is read: on the NPU it takes several seconds to build, and the song takes longer to arrive.
                    val opening = async<AutoCloseable?>(Dispatchers.Default) {
                        prepared ?: if (model == StemModel.SCNET) StemNetworks.openScnet(app, Accelerator.NPU) else StemNetworks.open(app, model, if (model == StemModel.OPEN_UNMIX) Accelerator.CPU else Accelerator.NPU)
                    }
                    val song = SongCapture.read(app, track, { set(track.id, StemProgress(StemProgress.Phase.READING, it * .2f, model)) }, { !jobs.containsKey(track.id) })
                    val network = opening.await()
                    if (song == null || network == null) { network?.close(); return@coroutineScope set(track.id, StemProgress(StemProgress.Phase.FAILED, model = model)) }
                    network.use {
                        val writer = store.Writer(track.id, model.id)
                        val where = (network as? OpenNetwork)?.where ?: (network as? OpenScnet)?.where ?: Accelerator.CPU
                        val progress = { it: Float -> set(track.id, StemProgress(StemProgress.Phase.SEPARATING, .2f + .8f * it, model, where)) }
                        val cancelled = { jobs[track.id]?.isActive != true }
                        val ok = when (network) {
                            is ScnetNetwork -> ScnetSeparator(network).separate(song, writer, progress, cancelled)
                            else -> StemSeparator(network as OpenNetwork, power = power.coerceIn(2, 4)).separate(song, writer, progress, cancelled)
                        }
                        if (ok) {
                            writer.finish(); store.evict(limitMb * 1_000_000L, keep = track.id)
                            set(track.id, StemProgress(StemProgress.Phase.DONE, 1f, model, where, (System.currentTimeMillis() - began) / 1000f))
                        } else { writer.abort(); set(track.id, null) }
                    }
                } } catch (e: CancellationException) { set(track.id, null); throw e }
                catch (e: Throwable) { Log.w("OpenTuneStems", "separating ${track.title} failed", e); set(track.id, StemProgress(StemProgress.Phase.FAILED, model = model)) }
                finally { freeLater() }
            }
        }
    }

    /** The networks stay built for the next song, and are freed when none has been separated for a while. */
    private fun freeLater() { scope.launch { delay(IDLE_MS); StemNetworks.closeIfIdle(IDLE_MS) } }

    private companion object { const val IDLE_MS = 120_000L }

    fun cancel(id: String) { jobs.remove(id)?.cancel(); set(id, null) }

    fun delete(id: String) { cancel(id); store.delete(id); set(id, null) }
}
