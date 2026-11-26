package io.github.lnrdo.mytidal.stems

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.lnrdo.mytidal.BuildConfig
import io.github.lnrdo.mytidal.core.stems.EnsembleNetwork
import io.github.lnrdo.mytidal.core.stems.ScnetNetwork
import io.github.lnrdo.mytidal.core.stems.StemNetwork
import io.github.lnrdo.mytidal.playback.dj.neural.Accelerator
import java.io.File
import java.nio.FloatBuffer

/** The networks that separate stems, and the file that holds each. Spleeter is small and runs on the NPU; Open-Unmix runs on the CPU. */
enum class StemModel(val id: String, val file: String, val frames: Int, val bins: Int, val binsFirst: Boolean) {
    /** Spleeter's U-Net: input [1, 2, 512, 1024] (channel, frame, bin), output [4, 1, 2, 512, 1024] (stem, batch, channel, frame, bin). */
    SPLEETER("spleeter", "spleeter_4stems.onnx", 512, 1024, false),

    /** Open-Unmix (umxhq): input [1, 2, 2049, 258] (channel, bin, frame), output [4, 1, 2, 2049, 258]. */
    OPEN_UNMIX("openunmix", "umxhq_4stems.onnx", 258, 2049, true),

    /** Both: the two networks vote on every bin, which keeps more of one stem out of another and carries the highs that Spleeter cuts. Slower: Open-Unmix runs on the CPU. */
    BOTH("both", "", 512, 2049, false),

    /**
     * SCNet (the best of these): about 11 s of complex spectrogram in, the four stems' spectrograms out. It comes as one file, which runs on the CPU, or in two
     * parts (the folder [partsFolder], with a manifest): the front (the encoder and the twelve LSTMs) on the CPU and the decoder on the NPU, about 15% quicker.
     */
    SCNET("scnet", "scnet_4stems.onnx", 474, 2049, false);

    fun path(context: Context) = File(File(context.filesDir, "stems"), file)
    fun installed(context: Context): Boolean = when (this) {
        BOTH -> SPLEETER.installed(context) && OPEN_UNMIX.installed(context)
        SCNET -> path(context).let { it.isFile && it.length() > 1_000_000 } || File(partsFolder(context), "manifest.json").isFile
        else -> path(context).let { it.isFile && it.length() > 1_000_000 }
    }

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: SPLEETER

        /**
         * The one model that this device uses: the best that is installed and that it runs well. SCNet gives the cleanest stems, but takes about 2.2 GB while
         * it separates, so it goes first only on a device with 7 GB of memory or more; then the two that vote (Both), then Spleeter, then Open-Unmix.
         */
        fun best(context: Context): StemModel? {
            val memory = android.app.ActivityManager.MemoryInfo().also { context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it) }.totalMem
            val order = if (memory >= 7_000_000_000L) listOf(SCNET, BOTH, SPLEETER, OPEN_UNMIX) else listOf(BOTH, SPLEETER, OPEN_UNMIX, SCNET)
            return order.firstOrNull { it.installed(context) }
        }
        /** The two parts of SCNet, as tools/model/export_scnet_two.py writes them. */
        fun partsFolder(context: Context) = File(File(context.filesDir, "stems"), "scnet_two")
    }
}

/** A stem network that is open, and where it runs. */
interface OpenNetwork : StemNetwork, AutoCloseable {
    val where: Accelerator
}

/** A stem network on ONNX Runtime. [where] is the accelerator that it opened on. Closing it frees its session. */
class OrtStemNetwork internal constructor(
    private val env: OrtEnvironment, private val session: OrtSession, private val model: StemModel, override val where: Accelerator,
) : OpenNetwork {
    override val frames get() = model.frames
    override val bins get() = model.bins
    private val inputName = session.inputNames.first()
    private val input = FloatArray(2 * model.frames * model.bins)

    @Synchronized
    override fun estimate(magnitude: FloatArray): FloatArray {
        val f = model.frames; val b = model.bins
        val shape: LongArray
        val data: FloatArray
        if (model.binsFirst) {
            // [channel][frame][bin] to [channel][bin][frame]
            for (c in 0 until 2) for (t in 0 until f) { val from = (c * f + t) * b; for (k in 0 until b) input[(c * b + k) * f + t] = magnitude[from + k] }
            data = input; shape = longArrayOf(1, 2, b.toLong(), f.toLong())
        } else { data = magnitude; shape = longArrayOf(1, 2, f.toLong(), b.toLong()) }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                val all = FloatArray(out.remaining()); out.get(all)
                if (!model.binsFirst) return all
                // [stem][channel][bin][frame] to [stem][channel][frame][bin]
                val turned = FloatArray(all.size)
                for (sc in 0 until 8) { val base = sc * f * b; for (k in 0 until b) for (t in 0 until f) turned[base + t * b + k] = all[base + k * f + t] }
                return turned
            }
        }
    }

    override fun close() { session.close() }
}

/** SCNet, open, and where it runs: [Accelerator.NPU] when some of it runs there. */
interface OpenScnet : ScnetNetwork, AutoCloseable {
    val where: Accelerator
}

/** SCNet on ONNX Runtime in one piece, on the CPU. Closing it frees the session. */
class OrtScnetNetwork internal constructor(private val env: OrtEnvironment, private val session: OrtSession) : OpenScnet {
    override val where get() = Accelerator.CPU
    override val frames get() = StemModel.SCNET.frames
    override val bins get() = StemModel.SCNET.bins
    private val inputName = session.inputNames.first()
    private var output = FloatArray(0)

    @Synchronized
    override fun run(input: FloatArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 4, bins.toLong(), frames.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                if (output.size != out.remaining()) output = FloatArray(out.remaining())
                out.get(output)
                return output
            }
        }
    }

    override fun close() { session.close() }
}

/**
 * SCNet in parts, each on its own session: the values that a part gives are handed to the parts that take them, by name, as the manifest says, as native
 * tensors, without a copy. A chunk runs the parts in order.
 */
class ScnetChain internal constructor(private val env: OrtEnvironment, private val parts: List<Part>, private val input: String, private val output: String) : OpenScnet {
    class Part(val name: String, val session: OrtSession, val inputs: List<String>, val outputs: List<String>, val onNpu: Boolean)

    override val frames get() = StemModel.SCNET.frames
    override val bins get() = StemModel.SCNET.bins
    override val where get() = if (parts.any { it.onNpu }) Accelerator.NPU else Accelerator.CPU
    private var result = FloatArray(0)

    @Synchronized
    override fun run(input: FloatArray): FloatArray {
        val results = ArrayList<OrtSession.Result>(parts.size)
        val spec = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 4, bins.toLong(), frames.toLong()))
        try {
            val values = HashMap<String, OnnxTensor>()
            values[this.input] = spec
            for (part in parts) {
                val r = part.session.run(part.inputs.associateWith { values.getValue(it) })
                results += r
                for (name in part.outputs) values[name] = r.get(name).get() as OnnxTensor
            }
            val out = values.getValue(output).floatBuffer
            if (result.size != out.remaining()) result = FloatArray(out.remaining())
            out.get(result)
            return result
        } finally { results.forEach { runCatching { it.close() } }; spec.close() }
    }

    override fun close() { parts.forEach { runCatching { it.session.close() } } }
}

/** What a song's separation with SCNet holds: the network, which goes back to the pool when this is closed. */
class ScnetLease internal constructor(private val network: OpenScnet, private val release: () -> Unit) : OpenScnet {
    override val where get() = network.where
    private var closed = false
    override val frames get() = network.frames
    override val bins get() = network.bins
    override fun run(input: FloatArray) = network.run(input)
    @Synchronized override fun close() { if (!closed) { closed = true; release() } }
}

/** Two networks that vote. Closing it frees neither: they belong to the pool. */
private class EnsembleOpen(private val first: OrtStemNetwork, private val second: OrtStemNetwork) : OpenNetwork {
    private val network = EnsembleNetwork(first, second)
    override val frames get() = network.frames
    override val bins get() = network.bins
    override val where get() = first.where
    override fun estimate(magnitude: FloatArray) = network.estimate(magnitude)
    override fun close() {}
}

/** What a separation holds while it runs: the network, which goes back to the pool when this is closed. */
private class Lease(private val network: OpenNetwork, private val release: () -> Unit) : OpenNetwork {
    private var closed = false
    override val frames get() = network.frames
    override val bins get() = network.bins
    override val where get() = network.where
    override fun estimate(magnitude: FloatArray) = network.estimate(magnitude)
    @Synchronized override fun close() { if (!closed) { closed = true; release() } }
}

/**
 * Opens the stem networks, and keeps them open. Building a network on the NPU takes seconds and a lot of native memory, and every network that was built and
 * freed again left some behind: after two or three songs the app was short of memory and the system restarted it. So a network is built once, shared by the
 * songs that follow, and freed when nobody has used it for a while ([closeIfIdle]) or when the system asks for memory back. The graph that the NPU builds
 * is also kept on disk, so that building it again, in a later run of the app, is quick and light.
 * The NPU is tried only for Spleeter, and an NPU that failed or took the app down is not tried again until the app is updated.
 */
object StemNetworks {
    private const val TAG = "OpenTuneStems"
    private val pool = HashMap<StemModel, OrtStemNetwork>()
    private var users = 0
    private var lastUsed = 0L

    /** For tests: with it off, every open builds a network of its own and closing frees it, as before. */
    @Volatile var pooling = true

    private fun crashMark(context: Context) = File(context.filesDir, "stems_trying_NPU_${BuildConfig.VERSION_CODE}")

    /** Whether the NPU has been found to crash or fail on this phone with this version of the app. */
    fun npuCrashed(context: Context): Boolean = crashMark(context).exists()

    /** Opens [model] on [wanted], falling back to the CPU. Returns null when the model file is not installed. Close what it returns when the song is done. */
    @Synchronized
    fun open(context: Context, model: StemModel, wanted: Accelerator): OpenNetwork? {
        if (!model.installed(context)) return null
        val network: OpenNetwork = if (model == StemModel.BOTH) {
            val first = obtain(context, StemModel.SPLEETER, wanted) ?: return null
            val second = obtain(context, StemModel.OPEN_UNMIX, Accelerator.CPU) ?: return null
            EnsembleOpen(first, second)
        } else obtain(context, model, wanted) ?: return null
        users++
        if (!pooling) return Lease(network) { users--; closeAll() }
        return Lease(network) { synchronized(this) { users--; lastUsed = SystemClock.elapsedRealtime() } }
    }

    private var scnet: OpenScnet? = null
    private var scnetWanted: Accelerator? = null

    /** Where the NPU graph of SCNet's decoder is kept: it is built once, in a few seconds, and read back in a fraction of one. */
    private fun npuGraphs(context: Context) = File(context.noBackupFilesDir, "stems_npu").apply { mkdirs() }

    /**
     * The part of SCNet that runs on the NPU: the decoder, which takes about 80 ms there and a second on the CPU. The front stays on the CPU: building its
     * encoder for the NPU takes more memory than the phone has, and its LSTMs, built one by one, take minutes each and run slower there with the time between.
     */
    private fun npuPart(kind: String, outputs: List<String>, output: String) = kind == "decoder" || (kind != "front" && output in outputs)

    private fun chipName() = (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE).filter { it.isLetterOrDigit() }
    private fun partCrashMark(context: Context, file: String) = File(context.filesDir, "stems_trying_NPU_${BuildConfig.VERSION_CODE}_scnet_$file")
    private fun names(array: org.json.JSONArray) = (0 until array.length()).map(array::getString)

    /** Whether opening SCNet on the NPU will build its graph first, which takes a few seconds. */
    fun scnetNeedsPreparing(context: Context): Boolean {
        val manifest = File(StemModel.partsFolder(context), "manifest.json")
        if (!manifest.isFile) return false
        val json = org.json.JSONObject(manifest.readText())
        val parts = json.getJSONArray("parts")
        return (0 until parts.length()).any { i ->
            val p = parts.getJSONObject(i)
            npuPart(p.getString("kind"), names(p.getJSONArray("outputs")), json.getString("output")) &&
                !File(npuGraphs(context), "${chipName()}_${p.getString("file")}").exists() && !partCrashMark(context, p.getString("file")).exists()
        }
    }

    /**
     * Opens SCNet, or returns null when it is not installed. On [Accelerator.NPU] its two parts are used, with the decoder on the NPU; a decoder that fails
     * there, or took the app down while it was built, runs on the CPU. [preparing] hears how far the building of the NPU graph is, from 0 to 1.
     * Close what it returns when the song is done.
     */
    @Synchronized
    fun openScnet(context: Context, wanted: Accelerator = Accelerator.NPU, preparing: (Float) -> Unit = {}): ScnetLease? {
        if (!StemModel.SCNET.installed(context)) return null
        if (scnet != null && scnetWanted != wanted && users == 0) { runCatching { scnet?.close() }; scnet = null }
        val network = scnet ?: runCatching { buildScnet(context, wanted, preparing) }.onFailure { Log.w(TAG, "scnet cannot run: ${it.message}") }.getOrNull() ?: return null
        scnet = network; scnetWanted = wanted
        users++
        return ScnetLease(network) { synchronized(this) { users--; lastUsed = SystemClock.elapsedRealtime(); if (!pooling) closeAll() } }
    }

    private fun buildScnet(context: Context, wanted: Accelerator, preparing: (Float) -> Unit): OpenScnet {
        val env = OrtEnvironment.getEnvironment()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        // Four threads, on the big cores: more, or the default, puts work on the small cores and is slower (measured on the S24 Ultra).
        fun cpu() = OrtSession.SessionOptions().apply { setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT); setIntraOpNumThreads(threads); setInterOpNumThreads(1) }
        val whole = StemModel.SCNET.path(context)
        val manifest = File(StemModel.partsFolder(context), "manifest.json")
        // On the CPU the network in one piece is quicker than its parts.
        if (!manifest.isFile || (wanted != Accelerator.NPU && whole.isFile)) return OrtScnetNetwork(env, cpu().use { env.createSession(whole.path, it) })
        val json = org.json.JSONObject(manifest.readText())
        val list = json.getJSONArray("parts")
        val output = json.getString("output")
        val toBuild = wanted == Accelerator.NPU && scnetNeedsPreparing(context)
        val npuCount = (0 until list.length()).count { i -> val p = list.getJSONObject(i); npuPart(p.getString("kind"), names(p.getJSONArray("outputs")), output) }
        var built = 0
        val parts = (0 until list.length()).map { i ->
            val p = list.getJSONObject(i)
            val file = p.getString("file")
            val inputs = names(p.getJSONArray("inputs"))
            val outputs = names(p.getJSONArray("outputs"))
            val path = File(StemModel.partsFolder(context), file).path
            val mark = partCrashMark(context, file)
            val npu = wanted == Accelerator.NPU && npuPart(p.getString("kind"), outputs, output) && !mark.exists()
            var session: OrtSession? = null
            if (npu) {
                val graph = File(npuGraphs(context), "${chipName()}_$file")
                fun options() = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    addConfigEntry("session.disable_cpu_ep_fallback", "1")
                    addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst", "enable_htp_fp16_precision" to "1", "vtcm_mb" to "8"))
                }
                // A build that takes the whole app down cannot report it, so a mark is left while it runs: that part stays on the CPU from then on.
                runCatching { mark.writeText("trying") }
                session = (if (graph.exists()) runCatching { options().use { env.createSession(graph.path, it) } }.onFailure { graph.delete() }.getOrNull() else null)
                    ?: runCatching {
                        options().apply { addConfigEntry("ep.context_enable", "1"); addConfigEntry("ep.context_embed_mode", "1"); addConfigEntry("ep.context_file_path", graph.path) }
                            .use { env.createSession(path, it) }
                    }.onFailure { Log.w(TAG, "scnet $file cannot run on the NPU: ${it.message}") }.getOrNull()
                if (session != null) mark.delete() else runCatching { mark.writeText("failed") }
                built++
                if (toBuild) preparing(built.toFloat() / npuCount)
            }
            ScnetChain.Part(file, session ?: cpu().use { env.createSession(path, it) }, inputs, outputs, session != null)
        }
        Log.i(TAG, "scnet in ${parts.size} parts, ${parts.count { it.onNpu }} on the NPU")
        return ScnetChain(env, parts, json.getString("input"), output)
    }

    /** Frees the networks when no song is using them and none has for [idleMs]. */
    @Synchronized
    fun closeIfIdle(idleMs: Long) {
        if (users == 0 && SystemClock.elapsedRealtime() - lastUsed >= idleMs) closeAll()
    }

    @Synchronized
    private fun closeAll() {
        for ((model, network) in pool) runCatching { network.close(); Log.i(TAG, "${model.id} freed") }
        pool.clear()
        scnet?.let { runCatching { it.close(); Log.i(TAG, "scnet freed") } }
        scnet = null
    }

    private fun obtain(context: Context, model: StemModel, wanted: Accelerator): OrtStemNetwork? {
        if (pooling) pool[model]?.let { return it }
        val network = build(context, model, wanted) ?: return null
        if (pooling) pool[model] = network else pool[model] = network
        return network
    }

    private fun build(context: Context, model: StemModel, wanted: Accelerator): OrtStemNetwork? {
        val env = OrtEnvironment.getEnvironment()
        val tryNpu = wanted == Accelerator.NPU && model == StemModel.SPLEETER && !npuCrashed(context)
        if (tryNpu) {
            val mark = crashMark(context)
            // A build that takes the whole app down cannot report it, so a mark is left while it runs.
            runCatching { mark.writeText("trying") }
            val began = SystemClock.elapsedRealtime()
            val opened = runCatching {
                val chip = (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE).filter { it.isLetterOrDigit() }
                val cached = File(context.cacheDir, "stems_spleeter_npu_$chip.onnx")
                fun options() = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    addConfigEntry("session.disable_cpu_ep_fallback", "1")
                    addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst", "enable_htp_fp16_precision" to "1"))
                }
                // The graph that the NPU builds is kept the first time, and read back later: much quicker, and without the memory that building it takes.
                val reused = if (cached.exists()) runCatching { options().use { env.createSession(cached.path, it) } }.onFailure { cached.delete() }.getOrNull() else null
                val session = reused ?: options().apply {
                    addConfigEntry("ep.context_enable", "1"); addConfigEntry("ep.context_embed_mode", "1"); addConfigEntry("ep.context_file_path", cached.path)
                }.use { env.createSession(model.path(context).path, it) }
                OrtStemNetwork(env, session, model, Accelerator.NPU)
            }
            if (opened.isSuccess) mark.delete() else runCatching { mark.writeText("failed") }
            opened.onSuccess { Log.i(TAG, "${model.id} on the NPU, opened in ${SystemClock.elapsedRealtime() - began} ms"); return it }
                .onFailure { Log.w(TAG, "${model.id} cannot run on the NPU: ${it.message}") }
        }
        return runCatching {
            val session = OrtSession.SessionOptions().apply { setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4)) }
                .use { env.createSession(model.path(context).path, it) }
            OrtStemNetwork(env, session, model, Accelerator.CPU)
        }.onFailure { Log.w(TAG, "${model.id} cannot run on the CPU: ${it.message}") }.getOrNull()
    }
}
