package io.github.lnrdo.mytidal

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.FloatBuffer
import org.junit.Test
import org.junit.runner.RunWith

/** Opens one ONNX file on the QNN back-end named by the argument "backend" (libQnnGpu.so or libQnnHtp.so) and runs it once, to find what a back-end cannot take. */
@RunWith(AndroidJUnit4::class)
class GpuProbeTest {
    @Test fun probe() {
        val args = InstrumentationRegistry.getArguments()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, args.getString("model")!!)
        val backend = args.getString("backend") ?: "libQnnGpu.so"
        val frames = (args.getString("frames") ?: "125").toLong()
        val input = args.getString("input") ?: "spect"
        val width = (args.getString("width") ?: "128").toLong()
        val env = OrtEnvironment.getEnvironment()
        val began = System.nanoTime()
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            addQnn(mapOf("backend_path" to backend))
        }
        Log.i("GpuProbe", "${file.name}: opening on $backend")
        env.createSession(file.path, options).use { s ->
            Log.i("GpuProbe", "${file.name}: opened in ${(System.nanoTime() - began) / 1_000_000} ms")
            OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray((frames * width).toInt()) { (it % 7) * 0.1f }), longArrayOf(1, frames, width)).use { x ->
                s.run(mapOf(input to x)).use { Log.i("GpuProbe", "${file.name}: ran") }
            }
        }
    }
}
