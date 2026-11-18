package io.github.yyan115.opentune.playback.dj.neural

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.yyan115.opentune.core.dj.neural.BeatDecoder
import io.github.yyan115.opentune.core.dj.neural.LogMel
import io.github.yyan115.opentune.core.dj.neural.NeuralBeats
import io.github.yyan115.opentune.core.dj.neural.Resampler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the network runs. AUTO takes the fastest one that the phone has: the NPU, then the GPU, then the CPU. */
enum class Accelerator { AUTO, NPU, GPU, CPU }

/** What the rhythm model did last, for the screen: where it ran, how long a song took, and how much of the last minute the accelerator spent on it. */
data class BeatModelState(
    val loaded: Accelerator? = null,
    /** The accelerators that could be opened on this phone. */
    val available: Set<Accelerator> = emptySet(),
    val lastSongMs: Long? = null,
    val lastSongSeconds: Double? = null,
    val lastWindowMs: Long? = null,
    /** The share of the last 60 s that the model was running, from 0 to 100. */
    val busyPercent: Double = 0.0,
    val error: String? = null,
)

/**
 * The Beat This! network (small0, MIT licence, CPJKU) run with ONNX Runtime. Qualcomm's QNN back-ends run it on the Hexagon NPU or the Adreno GPU, and
 * ONNX Runtime's own CPU code runs it anywhere; a part the NPU cannot take falls back to the CPU on its own. It reads a song's log-mel spectrogram 30 s
 * at a time and gives a chance of a beat and of a downbeat for each 20 ms, from which [BeatDecoder] reads the beats, the bars and the tempo.
 */
object BeatModel {
    private const val TAG = "OpenTuneBeatModel"
    /** The code that [BeatModelState.error] holds when no accelerator could open the model. */
    const val NO_ACCELERATOR = "NO_ACCELERATOR"
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private var sessionFor: Accelerator? = null
    private var logMel: LogMel? = null
    private var model: ByteArray? = null

    private val mutableState = MutableStateFlow(BeatModelState())
    val state: StateFlow<BeatModelState> = mutableState.asStateFlow()

    // The times that the model ran, to report how busy the accelerator was over the last minute.
    private val runs = ArrayDeque<Pair<Long, Long>>()

    private fun load(context: Context) {
        if (model == null) model = context.assets.open("beatthis/beat_this_small0.onnx").use { it.readBytes() }
        if (logMel == null) {
            val bytes = context.assets.open("beatthis/mel_fb_513x128.bin").use { it.readBytes() }
            val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            logMel = LogMel(FloatArray(floats.remaining()).also { floats.get(it) })
        }
    }

    private fun options(on: Accelerator): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        when (on) {
            Accelerator.NPU -> addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst", "enable_htp_fp16_precision" to "1", "htp_graph_finalization_optimization_mode" to "3"))
            Accelerator.GPU -> addQnn(mapOf("backend_path" to "libQnnGpu.so"))
            else -> setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
    }

    /** Opens the network on [wanted], or on the next one down when that cannot be opened. Returns where it opened. */
    @Synchronized
    fun open(context: Context, wanted: Accelerator): Accelerator? {
        load(context)
        val order = when (wanted) {
            Accelerator.AUTO, Accelerator.NPU -> listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU)
            Accelerator.GPU -> listOf(Accelerator.GPU, Accelerator.CPU)
            Accelerator.CPU -> listOf(Accelerator.CPU)
        }
        val current = sessionFor
        if (session != null && current != null && (current == wanted || wanted == Accelerator.AUTO)) return current
        session?.close(); session = null; sessionFor = null
        for (on in order) {
            val opened = runCatching { env.createSession(model!!, options(on)) }
            opened.onSuccess { s ->
                session = s; sessionFor = on
                Log.i(TAG, "the rhythm model runs on $on")
                mutableState.value = mutableState.value.copy(loaded = on, available = mutableState.value.available + on, error = null)
                return on
            }.onFailure { Log.w(TAG, "the rhythm model cannot run on $on: ${it.message}") }
        }
        mutableState.value = mutableState.value.copy(loaded = null, error = NO_ACCELERATOR)
        return null
    }

    /** Tries each accelerator once, so that the screen can say which ones this phone has. The one asked for is opened last. */
    fun probe(context: Context, then: Accelerator) {
        val found = HashSet<Accelerator>()
        for (on in listOf(Accelerator.NPU, Accelerator.GPU, Accelerator.CPU)) if (open(context, on) == on) found.add(on)
        mutableState.value = mutableState.value.copy(available = found)
        open(context, then)
    }

    /** The network's chance of a beat and of a downbeat, as logits, for one window of 1500 frames of 128 values. */
    private fun runWindow(s: OrtSession, window: FloatArray): Pair<FloatArray, FloatArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(1, BeatDecoder.WINDOW.toLong(), 128)).use { input ->
            s.run(mapOf("spect" to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val beat = (result.get(0).value as Array<FloatArray>)[0]
                @Suppress("UNCHECKED_CAST")
                val down = (result.get(1).value as Array<FloatArray>)[0]
                return beat to down
            }
        }
    }

    /** The network's answers for a whole spectrogram, as two series of logits, one value per frame. */
    @Synchronized
    fun logits(context: Context, wanted: Accelerator, spect: FloatArray, frames: Int): Pair<FloatArray, FloatArray>? {
        if (open(context, wanted) == null) return null
        val s = session ?: return null
        val starts = BeatDecoder.windowStarts(frames)
        val beats = ArrayList<FloatArray>(); val downs = ArrayList<FloatArray>()
        var windowMs = 0L
        for (start in starts) {
            val began = SystemClock.elapsedRealtime()
            val (b, d) = runWindow(s, BeatDecoder.window(spect, frames, start))
            windowMs = SystemClock.elapsedRealtime() - began
            beats.add(b); downs.add(d)
            note(began, windowMs)
        }
        mutableState.value = mutableState.value.copy(lastWindowMs = windowMs)
        return BeatDecoder.aggregate(beats, starts, frames) to BeatDecoder.aggregate(downs, starts, frames)
    }

    /** Finds the beats, bars and tempo of a whole song from its mono audio at [rate]. Returns null when the model cannot run. */
    fun analyze(context: Context, wanted: Accelerator, mono: FloatArray, rate: Int): NeuralBeats? {
        load(context)
        val began = SystemClock.elapsedRealtime()
        val audio = Resampler.resample(mono, rate, LogMel.SAMPLE_RATE)
        val (spect, frames) = logMel!!.compute(audio)
        val (beat, down) = logits(context, wanted, spect, frames) ?: return null
        val result = BeatDecoder.decode(beat, down)
        val took = SystemClock.elapsedRealtime() - began
        mutableState.value = mutableState.value.copy(lastSongMs = took, lastSongSeconds = mono.size.toDouble() / rate)
        Log.i(TAG, "a song of %.0f s in %d ms on %s: %d beats, %d downbeats, %s BPM".format(mono.size.toDouble() / rate, took, sessionFor, result.beats.size, result.downbeats.size,
            result.bpm?.let { "%.2f".format(it) } ?: "no"))
        return result
    }

    @Synchronized
    private fun note(began: Long, ms: Long) {
        runs.addLast(began to ms)
        val now = SystemClock.elapsedRealtime()
        while (runs.isNotEmpty() && runs.first().first < now - 60_000) runs.removeFirst()
        mutableState.value = mutableState.value.copy(busyPercent = runs.sumOf { it.second } / 600.0)
    }

    /** The share of the last minute that the model ran, refreshed for the screen. */
    @Synchronized
    fun refreshBusy() {
        val now = SystemClock.elapsedRealtime()
        while (runs.isNotEmpty() && runs.first().first < now - 60_000) runs.removeFirst()
        mutableState.value = mutableState.value.copy(busyPercent = runs.sumOf { it.second } / 600.0)
    }
}
