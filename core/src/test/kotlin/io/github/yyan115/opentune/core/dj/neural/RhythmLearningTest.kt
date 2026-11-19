package io.github.yyan115.opentune.core.dj.neural

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmLearningTest {
    @Test fun streamingResamplingMatchesResamplingAtOnce() {
        val from = 44_100
        val song = FloatArray(from * 3) { (0.4 * sin(2 * PI * 440.0 * it / from) + 0.2 * sin(2 * PI * 3000.0 * it / from)).toFloat() }
        val once = Resampler.resample(song, from, 22_050)
        val stream = StreamingResampler(from)
        var at = 0
        val chunk = FloatArray(4410)
        while (at < song.size) { val n = minOf(chunk.size, song.size - at); System.arraycopy(song, at, chunk, 0, n); stream.feed(chunk, n); at += n }
        stream.finish()
        val streamed = stream.result()
        assertEquals(once.size.toDouble(), streamed.size.toDouble(), 2.0)
        var worst = 0.0
        for (i in 100 until once.size - 100) worst = maxOf(worst, abs(once[i] - streamed[i]).toDouble())
        assertTrue("streamed and at once agree to 16 bits: $worst", worst < 1e-3)
    }

    @Test fun theResamplerCanBeEndedWhileAnotherThreadFeedsIt() {
        // The analyzer feeds while the preprocessor ends the song and reads it: this used to corrupt the read position and throw.
        repeat(20) {
            val stream = StreamingResampler(48_000)
            val chunk = FloatArray(9_600) { i -> kotlin.math.sin(i * 0.05).toFloat() }
            val feeder = Thread { repeat(400) { stream.feed(chunk, chunk.size) } }
            feeder.start()
            Thread.sleep(2)
            stream.finish()
            val result = stream.result()
            feeder.join()
            assertTrue(result.isNotEmpty())
            assertEquals("nothing is added after the end", result.size, stream.result().size)
        }
    }

    @Test fun theCalibrationTrustsTheNetworkUntilTaughtOtherwise() {
        val c = RhythmCalibration()
        assertEquals(124.0, c.predict(124.0), 1e-9)
        assertEquals(87.0, c.predict(87.0), 1e-9)
    }

    @Test fun answersTeachItWhichOctavePeopleMean() {
        val c = RhythmCalibration()
        // The listener keeps saying that swung songs the network reads near 170 are felt at half: hip-hop counted in eighths.
        for (k in 0 until 8) c.learn(RhythmAnswer("song$k", 168.0 + k, (168.0 + k) / 2, swing = 0.62, energy = 5.0))
        assertEquals("a new song like them", 86.0, c.predict(172.0, swing = 0.62, energy = 5.0), 0.01)
        // A loud straight song at 174 (drum and bass) is still read as it is.
        for (k in 0 until 6) c.learn(RhythmAnswer("dnb$k", 172.0 + k, 172.0 + k, swing = 0.5, energy = 8.5))
        assertEquals(174.0, c.predict(174.0, swing = 0.5, energy = 8.5), 0.01)
        assertEquals(86.0, c.predict(172.0, swing = 0.62, energy = 5.0), 0.01)
        assertTrue(c.accuracy() > 0.9)
    }

    @Test fun aNewAnswerForTheSameSongReplacesTheOld() {
        val c = RhythmCalibration()
        c.learn(RhythmAnswer("a", 120.0, 60.0)); c.learn(RhythmAnswer("a", 120.0, 120.0))
        assertEquals(1, c.answers.size)
        assertEquals(120.0, c.answers.single().confirmedBpm, 0.0)
    }

    @Test fun itIsSavedAndReadBackWithItsAnswers() {
        val c = RhythmCalibration()
        for (k in 0 until 8) c.learn(RhythmAnswer("id \"$k\"", 168.0 + k, (168.0 + k) / 2, dspBpm = 84.0 + k / 2.0, swing = 0.62))
        val again = RhythmCalibration.fromJson(c.toJson())
        assertEquals(8, again.answers.size)
        assertEquals("id \"3\"", again.answers[3].trackId)
        assertEquals(c.predict(172.0, swing = 0.62), again.predict(172.0, swing = 0.62), 1e-9)
        assertTrue(c.toJson().startsWith("{\"format\":\"opentune-rhythm\""))
    }
}
