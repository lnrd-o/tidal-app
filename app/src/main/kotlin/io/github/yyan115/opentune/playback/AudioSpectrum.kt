package io.github.yyan115.opentune.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import io.github.yyan115.opentune.core.Spectrum
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * The last samples that the player sent to the audio output, as one channel, for the spectrum analyzer.
 * The player writes them, and the screen reads them while the analyzer is open.
 */
object AudioSpectrum {
    private val ring = FloatArray(Spectrum.SIZE * 4)
    @Volatile private var written = 0L
    /** The rate of the samples in the ring. */
    @Volatile var sampleRate = 44_100
        private set
    private val viewers = AtomicInteger(0)
    /** The tap does nothing while nobody watches, so that the analyzer costs nothing when it is closed. */
    val enabled: Boolean get() = viewers.get() > 0

    /** A screen that shows the spectrum calls this when it appears, and [release] when it leaves. */
    fun watch() { viewers.incrementAndGet() }
    fun release() { if (viewers.decrementAndGet() <= 0) { viewers.set(0); clear() } }

    internal fun write(sample: Float, rate: Int) {
        sampleRate = rate
        val index = written
        ring[(index % ring.size).toInt()] = sample
        written = index + 1
    }

    /** Copies the latest [Spectrum.SIZE] samples into [into]. Returns false when fewer samples have arrived. */
    fun latest(into: FloatArray): Boolean {
        val end = written
        if (end < Spectrum.SIZE) return false
        val start = end - Spectrum.SIZE
        val size = ring.size
        var from = (start % size).toInt()
        // Two copies at most, instead of a modulo per sample.
        val first = minOf(Spectrum.SIZE, size - from)
        System.arraycopy(ring, from, into, 0, first)
        if (first < Spectrum.SIZE) { from = 0; System.arraycopy(ring, from, into, first, Spectrum.SIZE - first) }
        return true
    }

    fun clear() { written = 0 }

    /** The format that the tap was last configured for, for reports of an analyzer that stays empty. */
    @Volatile var configuration = "never configured"
        internal set

    /** How many audio sinks the player built with the tap, for the same reports. */
    @Volatile var sinks = 0
        internal set

    override fun toString() = "viewers=${viewers.get()} written=$written sinks=$sinks configuration=$configuration"
}

/**
 * What actually reaches the audio output: the decoded format as the player hands it on, and whether Android passes it unchanged.
 * The player writes it, and the signal path panel reads it.
 */
object SignalPath {
    /** The decoded format. Zero until the first track is configured. */
    data class Decoded(val sampleRate: Int, val bits: Int, val float: Boolean, val channels: Int)

    @Volatile var decoded: Decoded? = null
        internal set
    /** The name of the USB device that receives the stream unchanged, or null when Android's mixer handles it. */
    @Volatile var bitPerfectDevice: String? = null
        internal set
    /** The gain that volume normalization applies, in decibels. Zero when it is off. */
    @Volatile var normalizationDb: Float = 0f
        internal set
}

/**
 * Passes the audio on unchanged and lets [AudioSpectrum] see it. The samples of all channels are averaged,
 * and a rate above 48 kHz is brought down by whole steps, so that the analysis covers the same span of time at every rate.
 */
@UnstableApi
class SpectrumProcessor : BaseAudioProcessor() {
    private var encoding = C.ENCODING_INVALID
    private var channels = 2
    private var step = 1
    private var rate = 44_100
    private var sum = 0f
    private var count = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // A format that the tap cannot read, such as compressed audio sent on to a receiver, is left alone.
        if (inputAudioFormat.encoding !in SUPPORTED) return AudioFormat.NOT_SET
        AudioSpectrum.configuration = "encoding ${inputAudioFormat.encoding}, ${inputAudioFormat.channelCount} channels, ${inputAudioFormat.sampleRate} Hz"
        SignalPath.decoded = SignalPath.Decoded(inputAudioFormat.sampleRate, when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> 16; C.ENCODING_PCM_24BIT -> 24; else -> 32
        }, inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT, inputAudioFormat.channelCount)
        encoding = inputAudioFormat.encoding; channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        step = (inputAudioFormat.sampleRate / 48_000).coerceAtLeast(1); rate = inputAudioFormat.sampleRate / step
        sum = 0f; count = 0
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        if (AudioSpectrum.enabled) tap(inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN))
        replaceOutputBuffer(size).put(inputBuffer).flip()
    }

    private fun tap(buffer: ByteBuffer) {
        val bytes = when (encoding) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; else -> 4 }
        val frame = bytes * channels
        var frames = buffer.remaining() / frame
        var position = buffer.position()
        val scale = 1f / channels
        while (frames-- > 0) {
            var mixed = 0f
            for (channel in 0 until channels) {
                mixed += when (encoding) {
                    C.ENCODING_PCM_16BIT -> buffer.getShort(position) * (1f / 32768f)
                    C.ENCODING_PCM_24BIT -> ((buffer.get(position + 2).toInt() shl 16) or ((buffer.get(position + 1).toInt() and 0xFF) shl 8) or (buffer.get(position).toInt() and 0xFF)) * (1f / 8388608f)
                    C.ENCODING_PCM_32BIT -> buffer.getInt(position) * (1f / 2147483648f)
                    else -> buffer.getFloat(position)
                }
                position += bytes
            }
            sum += mixed * scale
            if (++count == step) { AudioSpectrum.write(sum / step, rate); sum = 0f; count = 0 }
        }
    }

    private companion object {
        val SUPPORTED = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
    }
}
