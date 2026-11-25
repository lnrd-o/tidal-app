package io.github.lnrdo.mytidal.stems

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.lnrdo.mytidal.BuildConfig
import io.github.lnrdo.mytidal.core.stems.StemNetwork
import io.github.lnrdo.mytidal.playback.dj.neural.Accelerator
import java.io.File
import java.nio.FloatBuffer

/** The two networks that separate stems, and the file that holds each. Spleeter is small and runs on the NPU; Open-Unmix runs on the CPU. */
enum class StemModel(val id: String, val file: String, val frames: Int, val bins: Int, val binsFirst: Boolean) {
    /** Spleeter's U-Net: input [1, 2, 512, 1024] (channel, frame, bin), output [4, 1, 2, 512, 1024] (stem, batch, channel, frame, bin). */
    SPLEETER("spleeter", "spleeter_4stems.onnx", 512, 1024, false),

    /** Open-Unmix (umxhq): input [1, 2, 2049, 258] (channel, bin, frame), output [4, 1, 2, 2049, 258]. */
    OPEN_UNMIX("openunmix", "umxhq_4stems.onnx", 258, 2049, true);

    fun path(context: Context) = File(File(context.filesDir, "stems"), file)
    fun installed(context: Context) = path(context).let { it.isFile && it.length() > 1_000_000 }

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: SPLEETER }
}

/** A stem network on ONNX Runtime. [where] is the accelerator that it opened on. */
class OrtStemNetwork internal constructor(
    private val env: OrtEnvironment, private val session: OrtSession, private val model: StemModel, val where: Accelerator,
) : StemNetwork, AutoCloseable {
    override val frames get() = model.frames
    override val bins get() = model.bins
    private val inputName = session.inputNames.first()
    private val input = FloatArray(2 * model.frames * model.bins)

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

/** Opens the stem networks. The NPU is tried only for Spleeter, and an NPU that failed or took the app down is not tried again until the app is updated. */
object StemNetworks {
    private const val TAG = "OpenTuneStems"

    private fun crashMark(context: Context) = File(context.filesDir, "stems_trying_NPU_${BuildConfig.VERSION_CODE}")

    /** Whether the NPU has been found to crash or fail on this phone with this version of the app. */
    fun npuCrashed(context: Context): Boolean = crashMark(context).exists()

    /** Opens [model] on [wanted], falling back to the CPU. Returns null when the model file is not installed. */
    @Synchronized
    fun open(context: Context, model: StemModel, wanted: Accelerator): OrtStemNetwork? {
        if (!model.installed(context)) return null
        val env = OrtEnvironment.getEnvironment()
        val tryNpu = wanted == Accelerator.NPU && model == StemModel.SPLEETER && !npuCrashed(context)
        if (tryNpu) {
            val mark = crashMark(context)
            // A build that takes the whole app down cannot report it, so a mark is left while it runs.
            runCatching { mark.writeText("trying") }
            val began = SystemClock.elapsedRealtime()
            val opened = runCatching {
                val options = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    addConfigEntry("session.disable_cpu_ep_fallback", "1")
                    addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst", "enable_htp_fp16_precision" to "1"))
                }
                OrtStemNetwork(env, env.createSession(model.path(context).path, options), model, Accelerator.NPU)
            }
            if (opened.isSuccess) mark.delete() else runCatching { mark.writeText("failed") }
            opened.onSuccess { Log.i(TAG, "${model.id} on the NPU, opened in ${SystemClock.elapsedRealtime() - began} ms"); return it }
                .onFailure { Log.w(TAG, "${model.id} cannot run on the NPU: ${it.message}") }
        }
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4)) }
        return runCatching { OrtStemNetwork(env, env.createSession(model.path(context).path, options), model, Accelerator.CPU) }
            .onFailure { Log.w(TAG, "${model.id} cannot run on the CPU: ${it.message}") }.getOrNull()
    }
}
