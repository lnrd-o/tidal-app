package io.github.lnrdo.mytidal.stems

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import io.github.lnrdo.mytidal.core.stems.Limiter
import io.github.lnrdo.mytidal.core.stems.Stem
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs

/**
 * Plays the stems of a song through one audio track, mixed live: each stem has a gain, a mute and a solo, and a change of any of them is heard within a
 * block (about 46 ms) with a ramp, so it does not click. The stems are read from their files as they play, each block in one read per file at its place
 * (no seeking), into a buffer of a third of a second, so that a busy phone does not starve the track. The mix goes through a soft [Limiter]: four stems
 * turned up can pass full scale, and cutting the top off was heard as cracks. This player is separate from the app's own: while it is open the app's player
 * is paused, and the position goes back to it when it closes.
 */
class StemPlayer(private val store: StemStore, private val id: String) {
    private val frames = store.length(id)
    /** The stems are kept with headroom; this brings them back to their level. */
    private val scale = store.gain(id) / 32768f
    private val files = Array(4) { s -> Array(2) { c -> RandomAccessFile(store.file(id, Stem.entries[s], c), "r") } }
    private val channels: Array<Array<FileChannel>> = Array(4) { s -> Array(2) { c -> files[s][c].channel } }
    private val gains = FloatArray(4) { 1f }
    private val muted = BooleanArray(4)
    @Volatile private var solo = -1
    @Volatile private var frame = 0
    @Volatile private var playing = false
    @Volatile private var running = true
    private var thread: Thread? = null
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT), RATE / 3 * 2 * 4))
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
        .setTransferMode(AudioTrack.MODE_STREAM).build()

    val durationMs: Long get() = frames * 1000L / RATE
    val positionMs: Long get() = frame * 1000L / RATE
    val isPlaying: Boolean get() = playing

    fun setGain(stem: Stem, value: Float) { gains[stem.ordinal] = value.coerceIn(0f, 1.5f) }
    fun setMuted(stem: Stem, on: Boolean) { muted[stem.ordinal] = on }

    /** Hears only [stem], or everything when it is null. */
    fun setSolo(stem: Stem?) { solo = stem?.ordinal ?: -1 }

    fun seekTo(ms: Long) { frame = (ms * RATE / 1000).toInt().coerceIn(0, maxOf(0, frames - 1)) }

    fun play() {
        if (playing) return
        playing = true
        if (thread == null) thread = Thread({ loop() }, "stem-player").also { it.start() }
        track.play()
    }

    fun pause() { playing = false; track.pause() }

    fun release() {
        running = false; playing = false
        runCatching { thread?.join(500) }
        runCatching { Log.i("OpenTuneStems", "stem player: ${track.underrunCount} underruns") }
        runCatching { track.stop() }; track.release()
        files.forEach { s -> s.forEach { runCatching { it.close() } } }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buffers = Array(4) { Array(2) { ByteBuffer.allocateDirect(BLOCK * 2).order(ByteOrder.LITTLE_ENDIAN) } }
        val out = FloatArray(BLOCK * 2)
        val limiter = Limiter(RATE)
        val last = FloatArray(4)
        for (s in 0 until 4) last[s] = target(s)
        var expected = -1
        while (running) {
            if (!playing) { Thread.sleep(20); continue }
            val start = frame
            if (start >= frames) { playing = false; track.pause(); frame = 0; continue }
            // A jump (a seek) starts the limiter afresh, so nothing of the old place is heard at the new one.
            if (start != expected) limiter.reset()
            val n = minOf(BLOCK, frames - start)
            for (s in 0 until 4) for (c in 0 until 2) {
                val b = buffers[s][c]
                b.clear(); b.limit(n * 2)
                var at = start * 2L
                while (b.hasRemaining()) { val got = channels[s][c].read(b, at); if (got <= 0) break; at += got }
                while (b.hasRemaining()) b.put(0)
                b.flip()
            }
            val now = FloatArray(4) { target(it) }
            for (i in 0 until n) {
                var l = 0f; var r = 0f
                val ramp = (i + 1f) / n
                for (s in 0 until 4) {
                    val g = last[s] + (now[s] - last[s]) * ramp
                    if (abs(g) < 1e-4f) continue
                    l += buffers[s][0].getShort(2 * i) * g
                    r += buffers[s][1].getShort(2 * i) * g
                }
                out[2 * i] = l * scale; out[2 * i + 1] = r * scale
            }
            for (s in 0 until 4) last[s] = now[s]
            limiter.process(out, n)
            track.write(out, 0, n * 2, AudioTrack.WRITE_BLOCKING)
            if (frame == start) frame = start + n
            expected = start + n
        }
    }

    private fun target(stem: Int): Float = if (solo >= 0) (if (stem == solo) gains[stem] else 0f) else if (muted[stem]) 0f else gains[stem]

    private companion object {
        const val RATE = 44_100
        const val BLOCK = 2048
    }
}
