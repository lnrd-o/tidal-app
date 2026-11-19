package io.github.lnrdo.mytidal

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lnrdo.mytidal.playback.dj.neural.GenreModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** YAMNet on this phone: it runs, and a song is read in about a second. The audio is the reference tone of the conversion script, played four times to be 16 s long. */
@RunWith(AndroidJUnit4::class)
class GenreModelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

    @Test fun theGenreModelReadsASongQuickly() {
        val b = ByteBuffer.wrap(testAssets.open("yamnet-ref/yamnet_wave16k.f32").use { it.readBytes() }).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val tone = FloatArray(b.remaining()).also { b.get(it) }
        // A 16 kHz tone is read as audio at 22,050 Hz here, as the preparer hands it over; the model brings it back to 16 kHz.
        val song = FloatArray(tone.size * 12) { tone[it % tone.size] }
        GenreModel.analyze(context, song, 22_050) // opens the model
        val began = System.nanoTime()
        val verdict = GenreModel.analyze(context, song, 22_050)
        val ms = (System.nanoTime() - began) / 1e6
        Log.i("OpenTuneGenreModel", "test: ${song.size / 22_050} s of audio in %.0f ms, genre ${verdict?.genre}".format(ms))
        assertNotNull(verdict)
        assertTrue("a song is read in under 5 s: $ms ms", ms < 5_000)
    }
}
