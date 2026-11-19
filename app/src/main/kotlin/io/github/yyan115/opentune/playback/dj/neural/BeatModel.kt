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
    /** The accelerators that were tried on this phone and could not run the model. */
    val failed: Set<Accelerator> = emptySet(),
    val error: String? = null,
)

/**
 * The Beat This! network (small0, MIT licence, CPJKU) run with ONNX Runtime. Qualcomm's QNN back-ends run it on the Hexagon NPU or the Adreno GPU, and
 * ONNX Runtime's own CPU code runs it anywhere; a part the NPU cannot take falls back to the CPU on its own. It reads a song's log-mel spectrogram 30 s
 * at a time and gives a chance of a beat and of a downbeat for each 20 ms, from which [BeatDecoder] reads the beats, the bars and the tempo.
 */
private val chipName: String
    get() = (if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else android.os.Build.HARDWARE).filter { it.isLetterOrDigit() }.ifBlank { "chip" }

object BeatModel {
    private const val TAG = "OpenTuneBeatModel"
    /** The code that [BeatModelState.error] holds when no accelerator could open the model. */
    const val NO_ACCELERATOR = "NO_ACCELERATOR"
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var session: OrtSession? = null
    private var sessionFor: Accelerator? = null
    private var logMel: LogMel? = null
    /** The frames in one window of the model that ships: 500 (10 s), which keeps the graph small enough for the NPU to build. */
    private var window = 500
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
            // On the NPU and the GPU the whole network must run there: a back-end that fails to start would otherwise hand every layer to the CPU in silence,
            // and the screen would claim an accelerator that does nothing.
            Accelerator.NPU, Accelerator.GPU -> addConfigEntry("session.disable_cpu_ep_fallback", "1")
            else -> {}
        }
        when (on) {
            // The default optimisation: the strongest one takes minutes and gigabytes to build the graph. The built graph is kept (see [open]).
            Accelerator.NPU -> addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst", "enable_htp_fp16_precision" to "1"))
            Accelerator.GPU -> addQnn(mapOf("backend_path" to "libQnnGpu.so"))
            else -> setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
    }

    /** Opens the network on [wanted], or on the next one down when that cannot be opened. Returns where it opened. */
    @Synchronized
    fun open(context: Context, wanted: Accelerator): Accelerator? {
        load(context)
        val order = when (wanted) {
            // AUTO stays on the CPU, which always works; the NPU and the GPU are tried only when the listener chooses them.
            Accelerator.NPU -> listOf(Accelerator.NPU, Accelerator.CPU)
            Accelerator.GPU -> listOf(Accelerator.GPU, Accelerator.CPU)
            Accelerator.AUTO, Accelerator.CPU -> listOf(Accelerator.CPU)
        }
        val current = sessionFor
        if (session != null && current != null && (current == wanted || wanted == Accelerator.AUTO)) return current
        session?.close(); session = null; sessionFor = null
        noteCrashes(context)
        for (on in order) {
            // An accelerator that failed once on this phone is not tried again in this run of the app: building the graph is long and heavy.
            if (on in mutableState.value.failed) continue
            val began = SystemClock.elapsedRealtime()
            // A build that takes the whole app down cannot report its failure, so a mark is left on disk while it runs: found at the next start, it says
            // that this accelerator crashed, and it is not tried again until the app is updated.
            val mark = crashMark(context, on)
            if (on != Accelerator.CPU) runCatching { mark.writeText("trying") }
            val opened = runCatching { create(context, on) }
            mark.delete()
            opened.onSuccess { s ->
                session = s; sessionFor = on
                Log.i(TAG, "the rhythm model runs on $on, opened in ${SystemClock.elapsedRealtime() - began} ms")
                mutableState.value = mutableState.value.copy(loaded = on, available = mutableState.value.available + on, error = null)
                return on
            }.onFailure {
                Log.w(TAG, "the rhythm model cannot run on $on: ${it.message}")
                if (on != Accelerator.CPU) mutableState.value = mutableState.value.copy(failed = mutableState.value.failed + on)
            }
        }
        mutableState.value = mutableState.value.copy(loaded = null, error = NO_ACCELERATOR)
        return null
    }

    private fun crashMark(context: Context, on: Accelerator) = java.io.File(context.filesDir, "beatthis_trying_${on}_${io.github.yyan115.opentune.BuildConfig.VERSION_CODE}")

    private var crashesNoted = false

    /** The accelerators whose build crashed the app before, from the marks that it left. */
    private fun noteCrashes(context: Context) {
        if (crashesNoted) return
        crashesNoted = true
        val crashed = listOf(Accelerator.NPU, Accelerator.GPU).filter { crashMark(context, it).exists() }.toSet()
        if (crashed.isNotEmpty()) {
            Log.w(TAG, "the rhythm model crashed the app while it was built for $crashed: they are not tried again")
            mutableState.value = mutableState.value.copy(failed = mutableState.value.failed + crashed)
        }
    }

    /**
     * A session on [on]. On the NPU, the graph that QNN builds for this chip is kept in the app's cache the first time (an "EP context" model), and later
     * sessions load it instead of building it again, which takes seconds and much memory.
     */
    private fun create(context: Context, on: Accelerator): OrtSession {
        if (on != Accelerator.NPU) return env.createSession(model!!, options(on))
        val cached = java.io.File(context.cacheDir, "beatthis_npu_${chipName}.onnx")
        if (cached.exists()) runCatching { return env.createSession(cached.path, options(on)) }.onFailure { cached.delete() }
        val build = options(on).apply {
            addConfigEntry("ep.context_enable", "1")
            addConfigEntry("ep.context_embed_mode", "1")
            addConfigEntry("ep.context_file_path", cached.path)
        }
        return env.createSession(model!!, build)
    }

    /** The network's chance of a beat and of a downbeat, as logits, for one window of 1500 frames of 128 values. */
    private fun runWindow(s: OrtSession, window: FloatArray): Pair<FloatArray, FloatArray> {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(window), longArrayOf(1, (window.size / 128).toLong(), 128)).use { input ->
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
        // The window of the model is read from its input.
        window = ((s.inputInfo["spect"]?.info as? ai.onnxruntime.TensorInfo)?.shape?.getOrNull(1)?.toInt() ?: window).coerceAtLeast(64)
        val starts = BeatDecoder.windowStarts(frames, window)
        val beats = ArrayList<FloatArray>(); val downs = ArrayList<FloatArray>()
        var windowMs = 0L
        for (start in starts) {
            val began = SystemClock.elapsedRealtime()
            val (b, d) = runWindow(s, BeatDecoder.window(spect, frames, start, window))
            windowMs = SystemClock.elapsedRealtime() - began
            beats.add(b); downs.add(d)
            note(began, windowMs)
        }
        mutableState.value = mutableState.value.copy(lastWindowMs = windowMs)
        return BeatDecoder.aggregate(beats, starts, frames, window) to BeatDecoder.aggregate(downs, starts, frames, window)
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
