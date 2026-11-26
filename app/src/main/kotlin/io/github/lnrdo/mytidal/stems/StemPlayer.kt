package io.github.lnrdo.mytidal.stems

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.github.lnrdo.mytidal.core.stems.Stem
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * Plays the stems of a song through one audio track, mixed live: each stem has a gain, a mute and a solo, and a change of any of them is heard within a
 * block (about 90 ms) with a ramp, so it does not click. The stems are read from their files as they play. This player is separate from the app's own: while
 * it is open the app's player is paused, and the position goes back to it when it closes.
 */
class StemPlayer(private val store: StemStore, private val id: String) {
    private val frames = store.length(id)
    private val files = Array(4) { s -> Array(2) { c -> RandomAccessFile(store.file(id, Stem.entries[s], c), "r") } }
    private val gains = FloatArray(4) { 1f }
    private val muted = BooleanArray(4)
    @Volatile private var solo = -1
    @Volatile private var frame = 0
    @Volatile private var playing = false
    @Volatile private var running = true
    private var thread: Thread? = null
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT), BLOCK * 4 * 2))
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
        if (thread == null) thread = Thread({ loop() }, "stem-player").also { it.priority = Thread.MAX_PRIORITY; it.start() }
        track.play()
    }

    fun pause() { playing = false; track.pause() }

    fun release() {
        running = false; playing = false
        runCatching { thread?.join(500) }
        runCatching { track.stop() }; track.release()
        files.forEach { s -> s.forEach { runCatching { it.close() } } }
    }

    private fun loop() {
        val bytes = Array(4) { Array(2) { ByteArray(BLOCK * 2) } }
        val out = ByteArray(BLOCK * 4)
        val last = FloatArray(4)
        for (s in 0 until 4) last[s] = target(s)
        while (running) {
            if (!playing) { Thread.sleep(20); continue }
            val start = frame
            if (start >= frames) { playing = false; track.pause(); frame = 0; continue }
            val n = minOf(BLOCK, frames - start)
            for (s in 0 until 4) for (c in 0 until 2) {
                val f = files[s][c]
                f.seek(start * 2L)
                val got = f.read(bytes[s][c], 0, n * 2)
                if (got < n * 2) java.util.Arrays.fill(bytes[s][c], maxOf(0, got), n * 2, 0)
            }
            val now = FloatArray(4) { target(it) }
            for (i in 0 until n) {
                var l = 0f; var r = 0f
                val ramp = (i + 1f) / n
                for (s in 0 until 4) {
                    val g = last[s] + (now[s] - last[s]) * ramp
                    if (abs(g) < 1e-4f) continue
                    l += ((bytes[s][0][2 * i].toInt() and 0xFF) or (bytes[s][0][2 * i + 1].toInt() shl 8)).toShort() * g
                    r += ((bytes[s][1][2 * i].toInt() and 0xFF) or (bytes[s][1][2 * i + 1].toInt() shl 8)).toShort() * g
                }
                val a = l.coerceIn(-32768f, 32767f).toInt(); val b = r.coerceIn(-32768f, 32767f).toInt()
                out[4 * i] = a.toByte(); out[4 * i + 1] = (a shr 8).toByte(); out[4 * i + 2] = b.toByte(); out[4 * i + 3] = (b shr 8).toByte()
            }
            for (s in 0 until 4) last[s] = now[s]
            track.write(out, 0, n * 4)
            if (frame == start) frame = start + n
        }
    }

    private fun target(stem: Int): Float = if (solo >= 0) (if (stem == solo) gains[stem] else 0f) else if (muted[stem]) 0f else gains[stem]

    private companion object {
        const val RATE = 44_100
        const val BLOCK = 4096
    }
}
