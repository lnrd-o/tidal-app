package io.github.lnrdo.mytidal

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.FloatBuffer
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs SCNet split in parts (files/stems/scnet_parts, with its manifest.json) with the blocks on "blocks" (npu, gpu, cpu) and the LSTMs on "lstms" (npu, gpu,
 * cpu). A part that cannot open where it is asked runs on the CPU. Logs the time of each part and of the whole chunk, and how far the result is from the
 * whole network on the CPU (saved by StemAccelTest with backend cpu).
 */
@RunWith(AndroidJUnit4::class)
class StemPartsTest {
    @Test fun chain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val blocks = args.getString("blocks") ?: "npu"
        val lstms = args.getString("lstms") ?: "cpu"
        val runs = (args.getString("runs") ?: "3").toInt()
        // Only these parts (file names without .onnx, comma separated) go where they are asked; the rest run on the CPU.
        val only = args.getString("only")?.split(',')?.toSet()
        val folder = File(context.filesDir, "stems/scnet_parts")
        val manifest = JSONObject(File(folder, "manifest.json").readText())
        val env = OrtEnvironment.getEnvironment()
        fun options(where: String) = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(4)
            when (where) {
                "npu" -> { addConfigEntry("session.disable_cpu_ep_fallback", "1"); addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst", "enable_htp_fp16_precision" to "1")) }
                "gpu" -> { addConfigEntry("session.disable_cpu_ep_fallback", "1"); addQnn(mapOf("backend_path" to "libQnnGpu.so")) }
            }
        }
        val parts = manifest.getJSONArray("parts")
        val sessions = ArrayList<Pair<OrtSession, String>>()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            val wanted = if (only != null && p.getString("file").removeSuffix(".onnx") !in only) "cpu" else if (p.getString("kind") == "lstm") lstms else blocks
            val began = System.nanoTime()
            val path = File(folder, p.getString("file")).path
            // The graph that the NPU builds is kept, and read back on the next run.
            val cached = File(context.cacheDir, "parts_${wanted}_${p.getString("file")}")
            val session = if (wanted == "cpu") null else runCatching {
                if (cached.exists()) options(wanted).use { env.createSession(cached.path, it) }
                else options(wanted).apply { addConfigEntry("ep.context_enable", "1"); addConfigEntry("ep.context_embed_mode", "1"); addConfigEntry("ep.context_file_path", cached.path) }.use { env.createSession(path, it) }
            }.onFailure { Log.w("StemParts", "${p.getString("file")} not on $wanted: ${it.message?.take(160)}") }.getOrNull()
            val opened = session ?: options("cpu").use { env.createSession(path, it) }
            val where = if (session != null) wanted else "cpu"
            Log.i("StemParts", "${p.getString("file")} on $where, opened in ${(System.nanoTime() - began) / 1_000_000} ms")
            sessions += opened to where
        }
        val random = java.util.Random(7)
        val spec = FloatArray(4 * 2049 * 474) { random.nextGaussian().toFloat() }
        var output = FloatArray(0)
        repeat(runs) { r ->
            val values = HashMap<String, OnnxValue>()
            val results = ArrayList<OrtSession.Result>()
            val times = StringBuilder()
            val t0 = System.nanoTime()
            values["spec"] = OnnxTensor.createTensor(env, FloatBuffer.wrap(spec), longArrayOf(1, 4, 2049, 474))
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                val ins = p.getJSONArray("inputs"); val outs = p.getJSONArray("outputs")
                val (session, where) = sessions[i]
                val t = System.nanoTime()
                val result = session.run((0 until ins.length()).associate { k -> ins.getString(k) to values.getValue(ins.getString(k)) as OnnxTensor })
                results += result
                for (k in 0 until outs.length()) values[outs.getString(k)] = result.get(outs.getString(k)).get()
                times.append("${p.getString("file").removeSuffix(".onnx")}:${where.first()}${(System.nanoTime() - t) / 1_000_000} ")
            }
            val b = (values.getValue("out") as OnnxTensor).floatBuffer
            output = FloatArray(b.remaining()); b.get(output)
            Log.i("StemParts", "blocks $blocks, lstms $lstms: chunk ${r + 1} in ${(System.nanoTime() - t0) / 1_000_000} ms")
            Log.i("StemParts", times.toString())
            results.forEach { it.close() }; (values["spec"] as OnnxTensor).close()
        }
        val reference = File(context.cacheDir, "accel_scnet_cpu.bin")
        if (reference.exists()) {
            val cpu = java.nio.ByteBuffer.wrap(reference.readBytes()).asFloatBuffer().let { bb -> FloatArray(bb.remaining()).also { bb.get(it) } }
            var err = 0.0; var energy = 0.0
            for (i in cpu.indices) { val d = (cpu[i] - output[i]).toDouble(); err += d * d; energy += cpu[i].toDouble() * cpu[i] }
            Log.i("StemParts", "blocks $blocks, lstms $lstms vs whole network on cpu: SNR ${"%.1f".format(10 * kotlin.math.log10(energy / maxOf(err, 1e-30)))} dB")
        }
        sessions.forEach { it.first.close() }
    }
}
