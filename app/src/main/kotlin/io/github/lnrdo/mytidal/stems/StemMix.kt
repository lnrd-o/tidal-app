package io.github.lnrdo.mytidal.stems

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import io.github.lnrdo.mytidal.core.stems.Limiter
import io.github.lnrdo.mytidal.core.stems.Stem
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The stems mix of the app, as the listener set it: on or off, and the level, mute and solo of each stem. It stays on for every song that has stems until the
 * listener turns it off, so the player of the app plays the mix: pausing, seeking, the notification and the next song work as always.
 */
object StemSwitch {
    @Volatile var store: StemStore? = null

    /** For each player, the offset that it adds to the positions of the stream that it plays (the audio processors see the shifted ones). */
    val streamOffsets = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    @Volatile var enabled = false
    @Volatile var gains = floatArrayOf(1f, 1f, 1f, 1f)
        private set
    @Volatile var muted = 0
        private set
    @Volatile var solo = -1
        private set

    /** Sets the mix: [levels] for each stem, [mutedBits] with a bit for each muted stem, and the soloed stem or -1. */
    fun set(levels: FloatArray, mutedBits: Int, soloStem: Int) {
        gains = FloatArray(4) { levels.getOrElse(it) { 1f }.coerceIn(0f, 1.5f) }
        muted = mutedBits
        solo = soloStem
    }

    /** What each stem is heard at now. */
    fun target(stem: Int): Float = if (solo >= 0) (if (stem == solo) gains[stem] else 0f) else if (muted and (1 shl stem) != 0) 0f else gains[stem]

    fun encode(levels: FloatArray, mutedBits: Int, soloStem: Int) = levels.joinToString(",") + ";" + mutedBits + ";" + soloStem

    /** The mix kept in the settings by [encode]; the default (all at 100%) when it cannot be read. */
    fun decode(text: String): Triple<FloatArray, Int, Int> = runCatching {
        val parts = text.split(';')
        Triple(parts[0].split(',').map { it.toFloat() }.toFloatArray(), parts[1].toInt(), parts[2].toInt())
    }.getOrElse { Triple(floatArrayOf(1f, 1f, 1f, 1f), 0, -1) }
}

/** The stems of one song, read from their files at any place. */
private class StemReader(store: StemStore, id: String) {
    val frames = store.length(id)
    val scale = store.gain(id) / 32768f
    private val files = Array(4) { s -> Array(2) { c -> RandomAccessFile(store.file(id, Stem.entries[s], c), "r") } }
    private var buffers = Array(4) { Array(2) { ByteBuffer.allocate(0) } }

    /** Reads [count] samples from [from] of every stem and channel; what lies past the end is silence. */
    fun load(from: Int, count: Int): Array<Array<ByteBuffer>> {
        if (buffers[0][0].capacity() < count * 2) buffers = Array(4) { Array(2) { ByteBuffer.allocate(count * 2 + 4096).order(ByteOrder.LITTLE_ENDIAN) } }
        for (s in 0 until 4) for (c in 0 until 2) {
            val b = buffers[s][c]
            b.clear(); b.limit(count * 2)
            var at = from.coerceAtLeast(0) * 2L
            while (b.hasRemaining()) { val got = files[s][c].channel.read(b, at); if (got <= 0) break; at += got }
            while (b.hasRemaining()) b.put(0)
        }
        return buffers
    }

    fun close() = files.forEach { s -> s.forEach { runCatching { it.close() } } }
}

/**
 * Replaces the audio of a song that has stems with the mix of [StemSwitch] while it is on. It knows the song and the place from what the player tells it when
 * a stream starts (the period and its position), and counts the samples from there; the stems, kept at 44.1 kHz, are read at the player's rate by linear
 * interpolation. A change of a level is ramped over the block, and the mix goes through a soft limiter. When off, the audio passes through unchanged.
 */
@UnstableApi
class StemProcessor(private val slot: Int) : BaseAudioProcessor() {
    private var encoding = C.ENCODING_PCM_16BIT
    private var rate = 44_100
    private var frame = 0L
    /** The position that the stream started at, as the player gave it (shifted), until [frame] is worked out from it at the first samples. */
    private var startUs = -1L
    private var song: String? = null
    private var reader: StemReader? = null
    private var lookAgain = 0L
    private var last = floatArrayOf(1f, 1f, 1f, 1f)
    private var limiter: Limiter? = null
    private var mix = FloatArray(0)
    private var applying = false

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount != 2 || (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT)) return AudioFormat.NOT_SET
        encoding = inputAudioFormat.encoding
        rate = inputAudioFormat.sampleRate
        limiter = Limiter(rate)
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        val timeline = streamMetadata.timeline
        val uid = streamMetadata.periodUid
        var id: String? = null
        val offsetUs = streamMetadata.positionOffsetUs
        if (uid != null && !timeline.isEmpty) {
            val index = timeline.getIndexOfPeriod(uid)
            if (index != C.INDEX_UNSET) {
                val period = timeline.getPeriod(index, Timeline.Period())
                id = timeline.getWindow(period.windowIndex, Timeline.Window()).mediaItem.mediaId
            }
        }
        if (id != song) { reader?.close(); reader = null; song = id; lookAgain = 0 }
        startUs = offsetUs
        limiter?.reset()
        applying = false
        Log.i("OpenTuneStems", "player $slot: ${id ?: "no song"}")
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        if (startUs >= 0) {
            // The player's offset is taken here and not at the flush: for the next song it is set between the two.
            val offset = StemSwitch.streamOffsets[slot] ?: 0L
            var position = startUs - offset
            if (position < 0 || position > DAY_US) position = (startUs % FIRST_OFFSET_US).coerceAtLeast(0)
            frame = position * rate / 1_000_000L
            lookAgain = minOf(lookAgain, frame)
            startUs = -1
            Log.i("OpenTuneStems", "player $slot: $song from ${position / 1000} ms")
        }
        val bytes = if (encoding == C.ENCODING_PCM_16BIT) 2 else 4
        val frames = size / (2 * bytes)
        val stems = if (StemSwitch.enabled) readerFor(frame) else null
        val out = replaceOutputBuffer(size)
        // A failure in the mix must not stop the music: the song plays as it is then.
        val mixed = stems != null && runCatching { render(stems, frames) }.onFailure { Log.w("OpenTuneStems", "the stems mix failed", it); reader?.close(); reader = null; lookAgain = frame + rate * 30L }.isSuccess
        if (!mixed) {
            out.put(inputBuffer)
            applying = false
        } else {
            inputBuffer.position(inputBuffer.limit())
            for (i in 0 until frames * 2) {
                if (encoding == C.ENCODING_PCM_16BIT) out.putShort((mix[i] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()) else out.putFloat(mix[i])
            }
        }
        out.flip()
        frame += frames
    }

    /** The reader of the song's stems, or null while it has none (looked for again every few seconds: they may be made while the song plays). */
    private fun readerFor(at: Long): StemReader? {
        reader?.let { return it }
        if (at < lookAgain) return null
        lookAgain = at + rate * 3L
        val store = StemSwitch.store ?: return null
        val id = song ?: return null
        if (!store.has(id)) return null
        return runCatching { StemReader(store, id) }.getOrNull()?.also { reader = it }
    }

    private fun render(stems: StemReader, frames: Int) {
        if (mix.size < frames * 2) mix = FloatArray(frames * 2)
        val step = 44_100.0 / rate
        val from = (frame * step).toLong().coerceIn(0L, stems.frames.toLong() + 1).toInt()
        val count = (frames * step).toInt() + 3
        val data = stems.load(from, count)
        val now = FloatArray(4) { StemSwitch.target(it) }
        // A start (or a switch on) takes the levels at once; a change while playing ramps.
        if (!applying) { last = now.copyOf(); limiter?.reset(); applying = true; Log.i("OpenTuneStems", "player $slot: playing the stems mix of $song at ${frame * 1000 / rate} ms") }
        for (i in 0 until frames) {
            val position = ((frame + i) * step - from).coerceIn(0.0, (count - 2).toDouble())
            val k = position.toInt()
            val t = (position - k).toFloat()
            val ramp = (i + 1f) / frames
            var l = 0f
            var r = 0f
            for (s in 0 until 4) {
                val g = last[s] + (now[s] - last[s]) * ramp
                if (abs(g) < 1e-4f) continue
                val left = data[s][0]
                val right = data[s][1]
                val l0 = left.getShort(2 * k)
                val r0 = right.getShort(2 * k)
                l += g * (l0 + (left.getShort(2 * k + 2) - l0) * t)
                r += g * (r0 + (right.getShort(2 * k + 2) - r0) * t)
            }
            mix[2 * i] = l * stems.scale
            mix[2 * i + 1] = r * stems.scale
        }
        last = now
        limiter?.process(mix, frames)
    }

    override fun onReset() { reader?.close(); reader = null; song = null }

    private companion object {
        /** The player's first offset, in microseconds, used when it has not given one. */
        const val FIRST_OFFSET_US = 1_000_000_000_000L
        const val DAY_US = 86_400_000_000L
    }
}
