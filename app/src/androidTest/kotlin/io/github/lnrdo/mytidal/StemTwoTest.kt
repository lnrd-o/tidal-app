package io.github.lnrdo.mytidal

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.FloatBuffer
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SCNet in two parts (files/stems/scnet_two: scnet_front.onnx, scnet_decoder.onnx): the front on the CPU with "threads" intra-op threads (a number, or
 * "default" for ONNX Runtime's own choice) and one inter-op thread, or on XNNPACK with "ep" = xnnpack; the decoder on the NPU ("decoder" = npu, or cpu), its
 * graph kept in the cache. The front's outputs go to the decoder as they are, as native tensors. Logs the decoder's build time and graph size, each stage of
 * each chunk, the peak resident memory, and the SNR against the whole network on the CPU (saved by StemAccelTest with backend cpu).
 */
@RunWith(AndroidJUnit4::class)
class StemTwoTest {
    private fun peakMb(): Long = File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmHWM:") }?.filter { it.isDigit() }?.toLong()?.div(1024) ?: -1

    @Test fun two() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val threads = args.getString("threads") ?: "4"
        val ep = args.getString("ep") ?: "cpu"
        val decoderOn = args.getString("decoder") ?: "npu"
        val runs = (args.getString("runs") ?: "3").toInt()
        val folder = File(context.filesDir, "stems/scnet_two")
        val env = OrtEnvironment.getEnvironment()
        val providers = OrtEnvironment.getAvailableProviders()
        Log.i("StemTwo", "providers: $providers")
        val label = "front ${args.getString("front") ?: "scnet_front.onnx"} $ep threads $threads, decoder $decoderOn"

        val frontOptions = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setInterOpNumThreads(1)
            if (threads != "default") setIntraOpNumThreads(threads.toInt())
            if (ep == "xnnpack") {
                check(OrtProvider.XNNPACK in providers) { "XNNPACK is not in this build" }
                addXnnpack(mapOf("intra_op_num_threads" to (if (threads == "default") "4" else threads)))
            }
        }
        var t = System.nanoTime()
        val frontFile = args.getString("front") ?: "scnet_front.onnx"
        val front = frontOptions.use { env.createSession(File(folder, frontFile).path, it) }
        Log.i("StemTwo", "$label: front opened in ${(System.nanoTime() - t) / 1_000_000} ms")

        val graph = File(context.cacheDir, "two_npu_scnet_decoder.onnx")
        val built = graph.exists()
        t = System.nanoTime()
        val decoder = if (decoderOn == "npu") {
            fun options() = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                addConfigEntry("session.disable_cpu_ep_fallback", "1")
                addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst", "enable_htp_fp16_precision" to "1", "vtcm_mb" to "8"))
            }
            if (built) options().use { env.createSession(graph.path, it) }
            else options().apply { addConfigEntry("ep.context_enable", "1"); addConfigEntry("ep.context_embed_mode", "1"); addConfigEntry("ep.context_file_path", graph.path) }
                .use { env.createSession(File(folder, "scnet_decoder.onnx").path, it) }
        } else OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }.use { env.createSession(File(folder, "scnet_decoder.onnx").path, it) }
        val decoderMs = (System.nanoTime() - t) / 1_000_000
        Log.i("StemTwo", "$label: decoder ${if (decoderOn != "npu") "on cpu" else if (built) "read from its cached graph" else "built"} in $decoderMs ms" +
            (if (decoderOn == "npu") ", graph ${graph.length()} bytes" else "") + ", peak RSS so far ${peakMb()} MB")

        val random = java.util.Random(7)
        val spec = FloatArray(4 * 2049 * 474) { random.nextGaussian().toFloat() }
        var output = FloatArray(0)
        repeat(runs) { r ->
            val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(spec), longArrayOf(1, 4, 2049, 474))
            val t0 = System.nanoTime()
            front.run(mapOf("spec" to input)).use { a ->
                val t1 = System.nanoTime()
                val feed = decoder.inputNames.associateWith { a.get(it).get() as OnnxTensor }
                decoder.run(feed).use { b ->
                    val t2 = System.nanoTime()
                    val out = (b.get(0) as OnnxTensor).floatBuffer
                    output = FloatArray(out.remaining()); out.get(output)
                    val t3 = System.nanoTime()
                    Log.i("StemTwo", "$label: chunk ${r + 1}: front ${(t1 - t0) / 1_000_000} ms, decoder ${(t2 - t1) / 1_000_000} ms, read out ${(t3 - t2) / 1_000_000} ms, total ${(t3 - t0) / 1_000_000} ms")
                }
            }
            input.close()
        }
        Log.i("StemTwo", "$label: peak RSS ${peakMb()} MB")
        val reference = File(context.cacheDir, "accel_scnet_cpu.bin")
        if (reference.exists()) {
            val cpu = java.nio.ByteBuffer.wrap(reference.readBytes()).asFloatBuffer().let { bb -> FloatArray(bb.remaining()).also { bb.get(it) } }
            var err = 0.0; var energy = 0.0; var worst = 0f
            for (i in cpu.indices) { val d = cpu[i] - output[i]; err += d.toDouble() * d; energy += cpu[i].toDouble() * cpu[i]; worst = maxOf(worst, kotlin.math.abs(d)) }
            Log.i("StemTwo", "$label vs whole network on cpu: SNR ${"%.1f".format(10 * kotlin.math.log10(energy / maxOf(err, 1e-30)))} dB, max diff $worst")
        }
        front.close(); decoder.close()
    }
}
