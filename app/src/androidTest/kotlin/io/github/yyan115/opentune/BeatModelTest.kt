package io.github.yyan115.opentune

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yyan115.opentune.core.dj.neural.BeatDecoder
import io.github.yyan115.opentune.core.dj.neural.LogMel
import io.github.yyan115.opentune.playback.dj.neural.Accelerator
import io.github.yyan115.opentune.playback.dj.neural.BeatModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Beat This! network on this phone's NPU, GPU and CPU, against the answers that PyTorch gave on the PC for the same spectrogram. */
@RunWith(AndroidJUnit4::class)
class BeatModelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun floats(name: String): FloatArray {
        val b = ByteBuffer.wrap(testAssets.open("beatthis-ref/$name").use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    @Test fun theNetworkRunsOnEachAcceleratorAndAgreesWithPyTorch() {
        val spect = floats("spect.f32")
        val frames = spect.size / LogMel.MELS
        val beatRef = floats("beat_logits.f32"); val downRef = floats("downbeat_logits.f32")
        val reference = BeatDecoder.decode(beatRef, downRef)
        var ran = 0
        // "-e accelerators NPU" tries only some: a back-end that crashes the process would end the test before the others.
        val wanted = InstrumentationRegistry.getArguments().getString("accelerators")?.split(',')?.map { Accelerator.valueOf(it.trim()) } ?: listOf(Accelerator.CPU, Accelerator.GPU, Accelerator.NPU)
        for (on in wanted) {
            val opened = BeatModel.open(context, on)
            if (opened != on) { Log.i("OpenTuneBeatModel", "$on: not available, opened $opened"); continue }
            BeatModel.logits(context, on, spect, frames) // the first run compiles the graph
            val began = System.nanoTime()
            val (beat, down) = BeatModel.logits(context, on, spect, frames)!!
            val ms = (System.nanoTime() - began) / 1e6
            var worst = 0f
            for (i in beat.indices) worst = maxOf(worst, abs(beat[i] - beatRef[i]), abs(down[i] - downRef[i]))
            val found = BeatDecoder.decode(beat, down)
            val same = found.beats.count { b -> reference.beats.any { abs(it - b) <= 0.021 } }
            Log.i("OpenTuneBeatModel", "%s: %d windows in %.0f ms (%.0f ms a window), largest logit difference %.4f, beats %d of %d match, %.2f BPM"
                .format(on, BeatDecoder.windowStarts(frames, 500).size, ms, ms / BeatDecoder.windowStarts(frames, 500).size, worst, same, reference.beats.size, found.bpm ?: 0.0))
            assertNotNull(found.bpm)
            assertEquals("$on tempo", 124.0, found.bpm!!, 0.5)
            assertTrue("$on finds the same beats: $same of ${reference.beats.size}", same >= reference.beats.size * 0.95)
            ran++
        }
        assertTrue("at least the CPU runs it", ran >= 1)
    }
}
