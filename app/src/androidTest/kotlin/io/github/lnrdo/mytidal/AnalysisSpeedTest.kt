package io.github.lnrdo.mytidal

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lnrdo.mytidal.playback.dj.DeckAnalyzer
import io.github.lnrdo.mytidal.playback.dj.DeckTapProcessor
import io.github.lnrdo.mytidal.playback.dj.DjMix
import io.github.lnrdo.mytidal.playback.dj.FastAnalysisSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * How fast a hidden player prepares a song on this device: the old way (a player that plays silence at four times the speed of the music) against the sink
 * that takes the audio as fast as it is decoded, and several songs at once on as many cores. The numbers go to the log under "OpenTuneSpeed".
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class AnalysisSpeedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val rate = 44_100
    private val seconds = 180
    private val bpm = 124.0

    /** A groove at [bpm]: a kick on each beat, a hat between, and a held chord, as 16-bit stereo WAV. */
    private fun song(name: String): File {
        val file = File(context.cacheDir, name)
        if (file.exists() && file.length() > seconds * rate * 4L) return file
        val frames = seconds * rate
        val pcm = ShortArray(frames * 2)
        val period = 60.0 / bpm * rate
        var beat = 0.0; var noise = 11
        while (beat < frames) {
            val at = beat.toInt()
            for (i in 0 until (0.12 * rate).toInt()) if (at + i < frames) {
                val v = (0.6 * exp(-i / (0.04 * rate)) * sin(2 * PI * 55.0 * i / rate) * 32767).toInt()
                pcm[(at + i) * 2] = (pcm[(at + i) * 2] + v).coerceIn(-32768, 32767).toShort(); pcm[(at + i) * 2 + 1] = pcm[(at + i) * 2]
            }
            val off = (beat + period / 2).toInt()
            for (i in 0 until 600) if (off + i < frames) {
                noise = noise * 1103515245 + 12345
                val v = (0.2 * ((noise ushr 16 and 0x7fff) / 16384.0 - 1) * exp(-i / 120.0) * 32767).toInt()
                pcm[(off + i) * 2] = (pcm[(off + i) * 2] + v).coerceIn(-32768, 32767).toShort(); pcm[(off + i) * 2 + 1] = pcm[(off + i) * 2]
            }
            beat += period
        }
        for (f in 0 until frames) {
            val c = (0.08 * (sin(2 * PI * 220.0 * f / rate) + sin(2 * PI * 277.2 * f / rate) + sin(2 * PI * 329.6 * f / rate)) * 32767).toInt()
            pcm[f * 2] = (pcm[f * 2] + c).coerceIn(-32768, 32767).toShort(); pcm[f * 2 + 1] = pcm[f * 2]
        }
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            val data = frames * 4
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2)
                .putInt(rate).putInt(rate * 4).putShort(4).putShort(16).put("data".toByteArray()).putInt(data)
            out.write(header.array())
            val bytes = java.nio.ByteBuffer.allocate(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(pcm)
            out.write(bytes.array())
        }
        return file
    }

    private fun <T> main(block: () -> T): T { var result: Any? = null; instrumentation.runOnMainSync { result = block() }; @Suppress("UNCHECKED_CAST") return result as T }

    private fun player(slot: Int, fast: Boolean): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                if (fast) FastAnalysisSink(slot)
                else DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(DeckTapProcessor(slot, always = true))).build()
        }
        return ExoPlayer.Builder(context, renderers).build()
    }

    private class Run(val millis: Long, val bpm: Double?, val seconds: Float)

    /** Reads [files] at once, one on each slot, and returns how long it took and what was found in each. */
    private fun read(files: List<File>, fast: Boolean): List<Run> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val slots = files.indices.map { DjMix.ANALYSIS_SLOT + it }
        val analyzers = slots.map { DeckAnalyzer(it, scope, { 0L }, estimateEvery = 20f) }
        val players = main {
            files.mapIndexed { i, file ->
                DjMix.streams[slots[i]].clear()
                player(slots[i], fast).apply {
                    volume = 0f
                    setPlaybackParameters(PlaybackParameters(4f))
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    playWhenReady = true
                    prepare()
                }
            }
        }
        analyzers.forEach { it.start() }
        val started = SystemClock.elapsedRealtime()
        val ends = LongArray(files.size)
        while (ends.any { it == 0L } && SystemClock.elapsedRealtime() - started < 240_000) {
            val states = main { players.map { it.playbackState to it.playerError } }
            states.forEachIndexed { i, (state, error) -> if (ends[i] == 0L && (state == Player.STATE_ENDED || error != null)) ends[i] = SystemClock.elapsedRealtime() }
            Thread.sleep(50)
        }
        // The analyzers catch up with the end and make one last estimate, as the preprocessor does.
        val counts = analyzers.map { it.estimates }
        analyzers.forEach { it.requestEstimate() }
        val waited = SystemClock.elapsedRealtime()
        while (analyzers.indices.any { analyzers[it].estimates == counts[it] } && SystemClock.elapsedRealtime() - waited < 8_000) Thread.sleep(20)
        val finished = SystemClock.elapsedRealtime()
        val runs = analyzers.indices.map { Run(maxOf(ends[it], finished) - started, analyzers[it].beat?.bpm, analyzers[it].seconds) }
        main { players.forEach { it.release() } }
        analyzers.forEach { it.stop() }
        slots.forEach { DjMix.streams[it].clear() }
        scope.cancel()
        return runs
    }

    private fun log(text: String) { Log.i("OpenTuneSpeed", text); println(text) }

    @Test fun fullSpeedIsFasterThanPlayingAndFindsTheSameTempo() {
        val file = song("speed-a.wav")
        val old = read(listOf(file), fast = false).single()
        val new = read(listOf(file), fast = true).single()
        log("one song of $seconds s, ${Runtime.getRuntime().availableProcessors()} cores: playing at 4x %.1f s (%.1fx), full speed %.1f s (%.1fx); tempo %.2f and %.2f, heard %.0f s and %.0f s"
            .format(old.millis / 1000.0, seconds * 1000.0 / old.millis, new.millis / 1000.0, seconds * 1000.0 / new.millis, old.bpm ?: 0.0, new.bpm ?: 0.0, old.seconds, new.seconds))
        assertNotNull(new.bpm)
        assertEquals("full speed hears the tempo", bpm, new.bpm!!, 1.0)
        assertTrue("and the whole song", new.seconds >= seconds - 2)
        assertTrue("it is faster than playing at 4x: ${new.millis} ms against ${old.millis} ms", new.millis < old.millis)
    }

    @Test fun severalSongsAtOnceUseMoreCores() {
        val files = listOf(song("speed-a.wav"), song("speed-b.wav"), song("speed-c.wav"))
        val one = read(files.take(1), fast = true).single().millis
        val three = read(files, fast = true)
        val wall = three.maxOf { it.millis }
        log("three songs side by side at full speed: %.1f s, against %.1f s for one song (%.1f times the throughput); tempos %s"
            .format(wall / 1000.0, one / 1000.0, 3.0 * one / wall, three.joinToString { "%.2f".format(it.bpm ?: 0.0) }))
        three.forEach { assertEquals(bpm, it.bpm ?: 0.0, 1.0); assertTrue(it.seconds >= seconds - 2) }
        assertTrue("three at once take less than three one after another", wall < 3 * one)
    }
}
