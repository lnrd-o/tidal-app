package io.github.yyan115.opentune.playback.dj

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Copies decoded audio, as stereo floats, into the ring of deck [slot]. A rate above 48 kHz is brought down by whole steps, and a flush (a seek, or a new
 * track) marks the ring so that stale audio is never read. It is shared by the tap in the audio chain of a player and by the sink of the hidden players.
 */
class TapWriter(private val slot: Int) {
    private var encoding = C.ENCODING_INVALID
    private var channels = 2
    private var step = 1
    private var rate = 44_100
    private var sumL = 0f
    private var sumR = 0f
    private var count = 0
    private var block = FloatArray(4096 * 2)

    /** The size of one frame of the input, in bytes. */
    var frameBytes = 4
        private set

    /** Returns false when the format cannot be read. */
    fun configure(encoding: Int, channels: Int, sampleRate: Int): Boolean {
        if (encoding !in SUPPORTED) return false
        this.encoding = encoding
        this.channels = channels.coerceAtLeast(1)
        step = (sampleRate / 48_000).coerceAtLeast(1)
        rate = sampleRate / step
        frameBytes = bytes() * this.channels
        sumL = 0f; sumR = 0f; count = 0
        return true
    }

    fun flush() {
        sumL = 0f; sumR = 0f; count = 0
        DjMix.streams[slot].ring?.markDiscontinuity()
    }

    private fun bytes() = when (encoding) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; else -> 4 }

    /** Writes the frames of [buffer], from its position to its limit, without moving its position. Returns the number of input frames. */
    fun write(buffer: ByteBuffer): Int {
        val bytes = bytes()
        val frame = bytes * channels
        val frames = buffer.remaining() / frame
        if (block.size < (frames / step + 2) * 2) block = FloatArray((frames / step + 2) * 2)
        val ring = DjMix.streams[slot].ensure(rate)
        var position = buffer.position()
        var produced = 0
        for (f in 0 until frames) {
            val l = sample(buffer, position)
            val r = if (channels > 1) sample(buffer, position + bytes) else l
            position += frame
            sumL += l; sumR += r
            if (++count == step) {
                block[produced * 2] = sumL / step; block[produced * 2 + 1] = sumR / step
                produced++; sumL = 0f; sumR = 0f; count = 0
            }
        }
        if (produced > 0) ring.write(block, produced)
        return frames
    }

    private fun sample(buffer: ByteBuffer, at: Int): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> buffer.getShort(at) * (1f / 32768f)
        C.ENCODING_PCM_24BIT -> ((buffer.get(at + 2).toInt() shl 16) or ((buffer.get(at + 1).toInt() and 0xFF) shl 8) or (buffer.get(at).toInt() and 0xFF)) * (1f / 8388608f)
        C.ENCODING_PCM_32BIT -> buffer.getInt(at) * (1f / 2147483648f)
        else -> buffer.getFloat(at)
    }

    companion object {
        val SUPPORTED = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
    }
}

/**
 * Sits in the audio chain of a deck. While [DjMix.routing] is on, it copies the decoded audio into the ring of its deck and hands the sink silence,
 * because the mixer plays the sound. While routing is off it passes the audio on unchanged.
 */
@UnstableApi
class DeckTapProcessor(private val slot: Int, private val always: Boolean = false) : BaseAudioProcessor() {
    private val writer = TapWriter(slot)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (!writer.configure(inputAudioFormat.encoding, inputAudioFormat.channelCount, inputAudioFormat.sampleRate)) return AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: StreamMetadata) { writer.flush() }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        if (always || DjMix.routing) {
            writer.write(inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN))
            inputBuffer.position(inputBuffer.limit())
            var left = size
            while (left > 0) { val n = minOf(left, ZEROS.size); out.put(ZEROS, 0, n); left -= n }
        } else out.put(inputBuffer)
        out.flip()
    }

    private companion object {
        val ZEROS = ByteArray(8192)
    }
}
