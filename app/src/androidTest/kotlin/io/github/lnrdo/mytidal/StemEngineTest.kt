package io.github.lnrdo.mytidal

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lnrdo.mytidal.core.stems.Fft
import io.github.lnrdo.mytidal.core.stems.StemSeparator
import io.github.lnrdo.mytidal.core.stems.StemSink
import io.github.lnrdo.mytidal.core.stems.StereoSource
import io.github.lnrdo.mytidal.playback.dj.neural.Accelerator
import io.github.lnrdo.mytidal.stems.StemModel
import io.github.lnrdo.mytidal.stems.StemNetworks
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Separates the 40 seconds of a real song that the rhythm tests carry (22.05 kHz mono, raised to 44.1 kHz) with a stem network on one accelerator, and reports
 * the time, the share of each band that each stem holds, and how far the four stems are from the mix when added. Arguments: "model" (spleeter or openunmix)
 * and "where" (npu or cpu). It writes the stems as WAV files to the files of the app, "stem_<model>_<where>_<stem>.wav".
 */
@RunWith(AndroidJUnit4::class)
class StemEngineTest {
    private val tag = "StemEngine"

    private fun song(): FloatArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("beatthis-ref/song_22050.f32").use { it.readBytes() }
        val mono = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }
        // Raised to 44.1 kHz by linear interpolation: the highs of the song are missing, which is fine for a test of the machinery.
        return FloatArray(mono.size * 2) { i -> val k = i / 2; if (i % 2 == 0) mono[k] else (mono[k] + mono.getOrElse(k + 1) { mono[k] }) / 2 }
    }

    private class Memory(n: Int) : StemSink {
        val out = Array(4) { Array(2) { FloatArray(n) } }
        override fun write(stem: Int, channel: Int, offset: Int, samples: FloatArray, count: Int) { System.arraycopy(samples, 0, out[stem][channel], offset, count) }
    }

    private fun bandEnergy(x: FloatArray): DoubleArray {
        val size = 4096; val fft = Fft(size); val bands = doubleArrayOf(0.0, 150.0, 800.0, 4000.0, 22050.0)
        val sums = DoubleArray(4); var pos = 0
        val re = DoubleArray(size); val im = DoubleArray(size)
        while (pos + size <= x.size) {
            for (i in 0 until size) { re[i] = x[pos + i].toDouble(); im[i] = 0.0 }
            fft.transform(re, im)
            for (k in 1 until size / 2) { val hz = k * 44100.0 / size; val p = re[k] * re[k] + im[k] * im[k]; for (b in 0 until 4) if (hz >= bands[b] && hz < bands[b + 1]) sums[b] += p }
            pos += size * 4
        }
        return sums
    }

    private fun wav(file: File, left: FloatArray, right: FloatArray) {
        val n = left.size
        val buffer = ByteBuffer.allocate(44 + n * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + n * 4).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(44100).putInt(44100 * 4).putShort(4).putShort(16)
        buffer.put("data".toByteArray()).putInt(n * 4)
        for (i in 0 until n) { buffer.putShort((left[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()); buffer.putShort((right[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        file.writeBytes(buffer.array())
    }

    @Test fun separate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val model = StemModel.of(args.getString("model"))
        val where = if (args.getString("where") == "npu") Accelerator.NPU else Accelerator.CPU
        val x = song(); val n = x.size
        val source = object : StereoSource {
            override val length = n
            override fun read(offset: Int, count: Int, left: FloatArray, right: FloatArray) {
                for (i in 0 until count) { val p = offset + i; val v = if (p < n) x[p] else 0f; left[i] = v; right[i] = v }
            }
        }
        val network = StemNetworks.open(context, model, where)
        assertTrue("model not installed: ${model.path(context)}", network != null)
        network!!.use {
            val sink = Memory(n)
            val began = System.nanoTime()
            val ok = StemSeparator(network).separate(source, sink)
            val seconds = (System.nanoTime() - began) / 1e9
            assertTrue(ok)
            val names = listOf("vocals", "drums", "bass", "other")
            var worst = 0f
            for (i in 0 until n) worst = maxOf(worst, abs(x[i] - (0 until 4).sumOf { sink.out[it][0][i].toDouble() }.toFloat()))
            Log.i(tag, "${model.id} on ${network.where}: ${"%.1f".format(n / 44100.0)} s of song in ${"%.2f".format(seconds)} s (${"%.3f".format(seconds / (n / 44100.0))} x real time); the four stems differ from the mix by at most ${"%.2e".format(worst)}")
            val total = DoubleArray(4); for (s in 0 until 4) bandEnergy(sink.out[s][0]).forEachIndexed { b, v -> total[b] += v }
            for (s in 0 until 4) {
                val rms = sqrt(sink.out[s][0].fold(0.0) { a, v -> a + v * v } / n)
                val bands = bandEnergy(sink.out[s][0])
                Log.i(tag, "  ${names[s]}: rms ${"%.4f".format(rms)}; share of each band (<150, 150-800, 0.8-4k, >4k Hz): " + bands.indices.joinToString(" ") { "%.0f%%".format(100 * bands[it] / total[it].coerceAtLeast(1e-12)) })
                wav(File(context.filesDir, "stem_${model.id}_${network.where.name.lowercase()}_${names[s]}.wav"), sink.out[s][0], sink.out[s][1])
            }
        }
    }
}
