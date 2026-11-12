package io.github.yyan115.opentune.playback.dj

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sits in the audio chain of a deck. While [DjMix.routing] is on, it copies the decoded audio, as stereo floats, into the ring of its deck
 * and hands the sink silence, because the mixer plays the sound. While routing is off it passes the audio on unchanged.
 * A rate above 48 kHz is brought down by whole steps, and a flush (a seek, or a new track) marks the ring so that stale audio is never read.
 */
@UnstableApi
class DeckTapProcessor(private val slot: Int) : BaseAudioProcessor() {
    private var encoding = C.ENCODING_INVALID
    private var channels = 2
    private var step = 1
    private var rate = 44_100
    private var sumL = 0f
    private var sumR = 0f
    private var count = 0
    private var block = FloatArray(4096 * 2)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding !in SUPPORTED) return AudioFormat.NOT_SET
        encoding = inputAudioFormat.encoding
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        step = (inputAudioFormat.sampleRate / 48_000).coerceAtLeast(1)
        rate = inputAudioFormat.sampleRate / step
        sumL = 0f; sumR = 0f; count = 0
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        sumL = 0f; sumR = 0f; count = 0
        DjMix.streams[slot].ring?.markDiscontinuity()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        if (DjMix.routing) {
            tap(inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN))
            inputBuffer.position(inputBuffer.limit())
            var left = size
            while (left > 0) { val n = minOf(left, ZEROS.size); out.put(ZEROS, 0, n); left -= n }
        } else out.put(inputBuffer)
        out.flip()
    }

    private fun tap(buffer: ByteBuffer) {
        val bytes = when (encoding) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; else -> 4 }
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
    }

    private fun sample(buffer: ByteBuffer, at: Int): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> buffer.getShort(at) * (1f / 32768f)
        C.ENCODING_PCM_24BIT -> ((buffer.get(at + 2).toInt() shl 16) or ((buffer.get(at + 1).toInt() and 0xFF) shl 8) or (buffer.get(at).toInt() and 0xFF)) * (1f / 8388608f)
        C.ENCODING_PCM_32BIT -> buffer.getInt(at) * (1f / 2147483648f)
        else -> buffer.getFloat(at)
    }

    private companion object {
        val SUPPORTED = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
        val ZEROS = ByteArray(8192)
    }
}
