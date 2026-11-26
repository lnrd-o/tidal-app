package io.github.lnrdo.mytidal

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lnrdo.mytidal.stems.StemModel
import java.io.File
import java.nio.FloatBuffer
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs a stem network ("model", default scnet) on one backend ("backend": cpu, npu, npu16, gpu, nnapi) with a fixed random chunk, and logs the time of each
 * run and how far the output is from the CPU's, which the CPU run saves. A backend that crashes takes the test down: run one backend per call.
 */
@RunWith(AndroidJUnit4::class)
class StemAccelTest {
    @Test fun bench() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val model = StemModel.of(args.getString("model") ?: "scnet")
        val backend = args.getString("backend") ?: "cpu"
        val runs = (args.getString("runs") ?: "3").toInt()
        val env = OrtEnvironment.getEnvironment()
        val shape = when (model) {
            StemModel.SCNET -> longArrayOf(1, 4, 2049, 474)
            StemModel.OPEN_UNMIX -> longArrayOf(1, 2, 2049, 258)
            else -> longArrayOf(1, 2, 512, 1024)
        }
        val random = java.util.Random(7)
        val input = FloatArray(shape.fold(1L) { a, b -> a * b }.toInt()) { if (model == StemModel.SCNET) random.nextGaussian().toFloat() else kotlin.math.abs(random.nextGaussian().toFloat()) }
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(4)
            when (backend) {
                "npu" -> addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst", "enable_htp_fp16_precision" to "1"))
                "npu16" -> addQnn(mapOf("backend_path" to "libQnnHtp.so", "htp_performance_mode" to "burst", "enable_htp_fp16_precision" to "1", "htp_graph_finalization_optimization_mode" to "3"))
                "gpu" -> addQnn(mapOf("backend_path" to "libQnnGpu.so"))
                "nnapi" -> addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16, ai.onnxruntime.providers.NNAPIFlags.CPU_DISABLED))
            }
        }
        val began = System.nanoTime()
        val session = env.createSession(model.path(context).path, options)
        Log.i("StemAccel", "${model.id} on $backend: session in ${(System.nanoTime() - began) / 1_000_000} ms")
        var output = FloatArray(0)
        repeat(runs) { r ->
            val t = System.nanoTime()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
                session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                    val b = (result[0] as OnnxTensor).floatBuffer
                    output = FloatArray(b.remaining()); b.get(output)
                }
            }
            Log.i("StemAccel", "${model.id} on $backend: run ${r + 1} in ${(System.nanoTime() - t) / 1_000_000} ms")
        }
        val reference = File(context.cacheDir, "accel_${model.id}_cpu.bin")
        if (backend == "cpu") {
            reference.outputStream().buffered().use { o -> val bb = java.nio.ByteBuffer.allocate(output.size * 4); bb.asFloatBuffer().put(output); o.write(bb.array()) }
        } else if (reference.exists()) {
            val bytes = reference.readBytes()
            val cpu = java.nio.ByteBuffer.wrap(bytes).asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }
            var worst = 0f; var scale = 0f; var err = 0.0; var energy = 0.0
            for (i in cpu.indices) { val d = kotlin.math.abs(cpu[i] - output[i]); worst = maxOf(worst, d); scale = maxOf(scale, kotlin.math.abs(cpu[i])); err += d.toDouble() * d; energy += cpu[i].toDouble() * cpu[i] }
            Log.i("StemAccel", "${model.id} on $backend vs cpu: max diff $worst (scale $scale), SNR ${"%.1f".format(10 * kotlin.math.log10(energy / maxOf(err, 1e-30)))} dB")
        }
        session.close(); options.close()
    }
}
