package io.github.lnrdo.mytidal

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lnrdo.mytidal.core.stems.StemSeparator
import io.github.lnrdo.mytidal.core.stems.StemSink
import io.github.lnrdo.mytidal.core.stems.StereoSource
import io.github.lnrdo.mytidal.playback.dj.neural.Accelerator
import io.github.lnrdo.mytidal.stems.StemModel
import io.github.lnrdo.mytidal.stems.StemNetworks
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Separates the 40 seconds of the reference song several times in one run of the app and logs what the process holds after each: the native memory that the
 * networks use (which the Java heap does not show), the resident memory and the swap. Arguments: "model", "runs" (default 4) and "pooling" (default true; false
 * builds and frees a network for each song, which is what the app did when it was restarted by the system after a few songs).
 */
@RunWith(AndroidJUnit4::class)
class StemMemoryTest {
    private fun status(key: String): Long = File("/proc/self/status").readLines().firstOrNull { it.startsWith("$key:") }?.filter { it.isDigit() }?.toLong()?.div(1024) ?: -1

    private class Discard : StemSink { override fun write(stem: Int, channel: Int, offset: Int, samples: FloatArray, count: Int) {} }

    @Test fun repeated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val model = StemModel.of(args.getString("model"))
        val runs = (args.getString("runs") ?: "4").toInt()
        StemNetworks.pooling = args.getString("pooling") != "false"
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("beatthis-ref/song_22050.f32").use { it.readBytes() }
        val mono = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }
        val x = FloatArray(mono.size * 2) { i -> mono[i / 2] }
        val source = object : StereoSource {
            override val length = x.size
            override fun read(offset: Int, count: Int, left: FloatArray, right: FloatArray) {
                for (i in 0 until count) { val p = offset + i; val v = if (p < x.size) x[p] else 0f; left[i] = v; right[i] = v }
            }
        }
        fun report(label: String) {
            val java = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576
            Log.i("StemMemory", "$label: native heap ${Debug.getNativeHeapAllocatedSize() / 1_048_576} MB, java heap $java MB, resident ${status("VmRSS")} MB, swap ${status("VmSwap")} MB")
        }
        report("start")
        repeat(runs) { run ->
            val began = System.nanoTime()
            val network = StemNetworks.open(context, model, Accelerator.NPU)
            assertTrue("model not installed", network != null)
            val opened = (System.nanoTime() - began) / 1_000_000
            val ok = network!!.use { StemSeparator(network, power = 3).separate(source, Discard()) }
            assertTrue(ok)
            report("after song ${run + 1} (network ready in $opened ms, ${(System.nanoTime() - began) / 1_000_000} ms in all)")
        }
        StemNetworks.closeIfIdle(0)
        report("after freeing the networks")
        StemNetworks.pooling = true
    }
}
