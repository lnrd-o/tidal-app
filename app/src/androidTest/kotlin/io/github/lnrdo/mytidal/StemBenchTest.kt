package io.github.lnrdo.mytidal

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.FloatBuffer
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Times one stem-separation network on one accelerator, for choosing the model of the stems feature. The ONNX file is in the files of the app (argument
 * "model"); "where" is cpu, npu or gpu; "seconds" is how much audio one run covers, to give the speed as a share of real time. The inputs take the shapes
 * that the file declares, filled with a fixed pattern. It reports the time to open the network (graph build), the first run and the mean of the next.
 */
@RunWith(AndroidJUnit4::class)
class StemBenchTest {
    @Test fun bench() {
        val args = InstrumentationRegistry.getArguments()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, args.getString("model")!!)
        val where = args.getString("where") ?: "cpu"
        val seconds = (args.getString("seconds") ?: "1").toDouble()
        val runs = (args.getString("runs") ?: "3").toInt()
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            when (where) {
                "npu" -> {
                    addQnn(mapOf("backend_path" to "libQnnHtp.so", "enable_htp_fp16_precision" to "1", "htp_performance_mode" to "burst"))
                    addConfigEntry("session.disable_cpu_ep_fallback", "0")
                }
                "gpu" -> addQnn(mapOf("backend_path" to "libQnnGpu.so"))
                else -> setIntraOpNumThreads(4)
            }
        }
        val tag = "StemBench"
        val began = System.nanoTime()
        Log.i(tag, "${file.name} on $where: opening (${file.length() / 1_000_000} MB)")
        val report = Bundle()
        try {
            env.createSession(file.path, options).use { session ->
                val openMs = (System.nanoTime() - began) / 1_000_000
                val inputs = session.inputInfo.mapValues { (_, info) ->
                    val shape = (info.info as TensorInfo).shape
                    val count = shape.fold(1L) { a, b -> a * b }.toInt()
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(count) { ((it * 7919) % 1000) / 1000f - .5f }), shape)
                }
                val times = ArrayList<Long>()
                repeat(runs + 1) {
                    val t = System.nanoTime()
                    session.run(inputs).use { }
                    times.add((System.nanoTime() - t) / 1_000_000)
                }
                inputs.values.forEach { it.close() }
                val steady = times.drop(1).average()
                val line = "${file.name} on $where: open $openMs ms, first ${times[0]} ms, then %.0f ms per run, %.2f s of audio a run, %.3f × real time".format(steady, seconds, steady / 1000 / seconds)
                Log.i(tag, line)
                report.putString("result", line)
            }
        } catch (e: Throwable) {
            val line = "${file.name} on $where: FAILED ${e.javaClass.simpleName}: ${e.message?.take(300)}"
            Log.w(tag, line)
            report.putString("result", line)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, report)
    }
}
