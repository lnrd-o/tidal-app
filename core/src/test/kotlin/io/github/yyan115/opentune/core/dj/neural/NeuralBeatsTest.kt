package io.github.yyan115.opentune.core.dj.neural

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin side of the Beat This! model against the Python reference made by the conversion script (app/src/androidTest/assets/beatthis-ref). */
class NeuralBeatsTest {
    private val ref = File("../app/src/androidTest/assets/beatthis-ref")
    private val filterbank = floats(File("../app/src/main/assets/beatthis/mel_fb_513x128.bin"))

    private fun floats(file: File): FloatArray {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    @Test fun theSpectrogramIsTheOneTheNetworkWasTrainedOn() {
        val song = floats(File(ref, "song_22050.f32"))
        val expected = floats(File(ref, "spect.f32"))
        val (spect, frames) = LogMel(filterbank).compute(song)
        assertEquals(expected.size / LogMel.MELS, frames)
        var worst = 0f
        for (i in expected.indices) worst = maxOf(worst, abs(spect[i] - expected[i]))
        println("largest difference from torchaudio: $worst")
        assertTrue("within 1e-3 of torchaudio: $worst", worst < 1e-3f)
    }

    @Test fun theBeatsAreReadFromTheLogitsAsBeatThisReadsThem() {
        val beat = floats(File(ref, "beat_logits.f32")); val down = floats(File(ref, "downbeat_logits.f32"))
        val json = File(ref, "reference.json").readText()
        fun list(name: String) = Regex("\"$name\": \\[([^\\]]*)]").find(json)!!.groupValues[1].split(',').map { it.trim().toDouble() }.toDoubleArray()
        val result = BeatDecoder.decode(beat, down)
        assertArrayEquals(list("beats"), result.beats, 1e-6)
        assertArrayEquals(list("downbeats"), result.downbeats, 1e-6)
        // The fitted tempo is finer than the median gap of 20 ms frames: the song is at 124.
        println("tempo fitted ${result.bpm}")
        assertEquals(124.0, result.bpm!!, 0.3)
    }

    @Test fun theWindowsCoverTheSongAsBeatThisCutsIt() {
        assertArrayEquals(intArrayOf(-6), BeatDecoder.windowStarts(1000))
        // 2001 frames: the first window from -6, the last one moved to end with the song.
        assertArrayEquals(intArrayOf(-6, 2001 - 1494), BeatDecoder.windowStarts(2001))
        val starts = BeatDecoder.windowStarts(9000)
        assertEquals(-6, starts.first()); assertEquals(9000 - 1494, starts.last())
        for (k in 1 until starts.size - 1) assertEquals(1488, starts[k] - starts[k - 1])
        // Each frame gets the first window's answer.
        val windows = starts.map { s -> FloatArray(BeatDecoder.WINDOW) { t -> (s + t).toFloat() } }
        val all = BeatDecoder.aggregate(windows, starts, 9000)
        for (f in 0 until 9000) assertEquals(f.toFloat(), all[f], 0f)
    }

    @Test fun resamplingKeepsAToneAndItsLength() {
        val from = 44_100
        val tone = FloatArray(from) { (0.5 * sin(2 * PI * 1000.0 * it / from)).toFloat() }
        val out = Resampler.resample(tone, from, 22_050)
        assertEquals(22_050, out.size)
        // The same tone at the new rate, away from the edges.
        var worst = 0.0
        for (i in 200 until out.size - 200) worst = maxOf(worst, abs(out[i] - 0.5 * sin(2 * PI * 1000.0 * i / 22_050)))
        assertTrue("worst $worst", worst < 0.01)
        assertEquals(22_050 * 2, Resampler.resample(FloatArray(96_000), 48_000, 22_050).size)
    }
}
