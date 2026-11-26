package io.github.lnrdo.mytidal.playback.usb

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioSink
import io.github.lnrdo.mytidal.playback.AudioSpectrum
import io.github.lnrdo.mytidal.playback.dj.DjMix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * Plays the decoded audio on a USB DAC through the app's own driver ([UsbDac]). The samples are copied into the DAC's format without any change: a 16-bit or
 * 24-bit song arrives in the DAC's 24-bit (or 16 or 32-bit) slots exactly. Only when the DAC has no volume control of its own, or the player asks for less than
 * full volume, are the samples scaled. The clock of the player is the audio that the DAC has taken.
 */
@UnstableApi
class UsbAudioSink : AudioSink {
    private var stream: UsbStream? = null
    private var encoding = C.ENCODING_PCM_16BIT
    private var channels = 2
    private var rate = 44_100
    private var playing = false
    private var ended = false
    private var startUs = C.TIME_UNSET
    private var playedAtStart = 0L
    private var volume = 1f
    private var parameters = PlaybackParameters.DEFAULT
    private var attributes = AudioAttributes.DEFAULT
    private var skipSilence = false
    private var scratch: ByteBuffer = ByteBuffer.allocateDirect(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)

    private fun inputBytes() = when (encoding) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; else -> 4 }

    /** Whether the DAC can take this format now. */
    fun accepts(format: Format): Boolean = MimeTypes.AUDIO_RAW == format.sampleMimeType && format.pcmEncoding in ENCODINGS && UsbDac.canPlay(format.sampleRate, format.channelCount)

    override fun setListener(listener: AudioSink.Listener) {}
    override fun setPlayerId(playerId: PlayerId?) {}
    override fun supportsFormat(format: Format): Boolean = accepts(format)
    override fun getFormatSupport(format: Format): Int = if (accepts(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val s = stream ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (startUs == C.TIME_UNSET) return AudioSink.CURRENT_POSITION_NOT_SET
        return startUs + ((s.framesPlayed - playedAtStart) * 1_000_000.0 / rate).roundToLong()
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) = configure(inputFormat)
    override fun configure(config: AudioSink.AudioSinkConfig) = configure(config.format)

    private fun configure(format: Format) {
        val same = stream?.let { it.running && it.rate == format.sampleRate && channels == format.channelCount } == true
        encoding = format.pcmEncoding; channels = format.channelCount; rate = format.sampleRate
        if (!same) {
            stream?.let { UsbDac.closeStream(it) }
            stream = UsbDac.openStream(rate, channels) ?: throw AudioSink.ConfigurationException("The USB DAC cannot play $rate Hz, $channels channels", format)
        }
        startUs = C.TIME_UNSET; ended = false
    }

    override fun play() { playing = true; stream?.paused(false) }
    override fun pause() { playing = false; stream?.paused(true) }
    override fun handleDiscontinuity() {}

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val s = stream ?: return false
        if (!s.running) throw AudioSink.WriteException(-1, Format.Builder().build(), false)
        if (startUs == C.TIME_UNSET) { startUs = presentationTimeUs; playedAtStart = s.framesPlayed }
        val inBytes = inputBytes()
        val inFrame = inBytes * channels
        val outSlot = s.setting.subslotBytes
        val outFrame = s.frameBytes
        // As many frames as fit in the scratch buffer and the room that the ring has.
        val room = (s.setting.frameBytes.let { (DAC_RING_BYTES(s) - s.queuedBytes) / it }).coerceAtLeast(0)
        val frames = minOf(buffer.remaining() / inFrame, scratch.capacity() / outFrame, room)
        if (frames == 0) return !buffer.hasRemaining()
        val gain = volume * UsbDac.softwareGain
        val exact = gain >= 1f
        val input = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        scratch.clear()
        var at = input.position()
        val spectrum = AudioSpectrum.enabled && !DjMix.routing
        for (f in 0 until frames) {
            var mono = 0f
            for (ch in 0 until channels) {
                // Every sample becomes a 32-bit value aligned to the top, then its top bytes are kept: exact whenever the DAC has at least the song's bits.
                var v: Int = when (encoding) {
                    C.ENCODING_PCM_16BIT -> input.getShort(at).toInt() shl 16
                    C.ENCODING_PCM_24BIT -> ((input.get(at + 2).toInt() shl 24) or ((input.get(at + 1).toInt() and 0xFF) shl 16) or ((input.get(at).toInt() and 0xFF) shl 8))
                    C.ENCODING_PCM_32BIT -> input.getInt(at)
                    else -> { val x = input.getFloat(at).toDouble() * 2147483648.0; x.coerceIn(-2147483648.0, 2147483647.0).toInt() }
                }
                if (!exact) v = (v * gain.toDouble()).toInt()
                if (spectrum) mono += v / 2147483648f
                at += inBytes
                for (b in 4 - outSlot until 4) scratch.put(((v shr (8 * b)) and 0xFF).toByte())
            }
            if (spectrum) AudioSpectrum.write(mono / channels, rate)
        }
        val written = s.write(scratch, frames * outFrame)
        if (written < 0) throw AudioSink.WriteException(-1, Format.Builder().build(), false)
        buffer.position(buffer.position() + (written / outFrame) * inFrame)
        return !buffer.hasRemaining()
    }

    @Suppress("FunctionName")
    private fun DAC_RING_BYTES(s: UsbStream) = s.rate * s.frameBytes / 4

    override fun playToEndOfStream() { ended = true }
    override fun isEnded(): Boolean = ended && (stream?.queuedBytes ?: 0) == 0
    override fun hasPendingData(): Boolean = (stream?.queuedBytes ?: 0) > 0
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) { parameters = playbackParameters }
    override fun getPlaybackParameters(): PlaybackParameters = parameters
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) { skipSilence = skipSilenceEnabled }
    override fun getSkipSilenceEnabled(): Boolean = skipSilence
    override fun setAudioAttributes(audioAttributes: AudioAttributes) { attributes = audioAttributes }
    override fun getAudioAttributes(): AudioAttributes = attributes
    override fun setAudioSessionId(audioSessionId: Int) {}
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}
    override fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET
    override fun enableTunnelingV21() {}
    override fun disableTunneling() {}
    override fun setVolume(volume: Float) { this.volume = volume.coerceIn(0f, 1f) }

    override fun flush() {
        stream?.flush()
        startUs = C.TIME_UNSET; ended = false
    }

    override fun reset() {
        flush()
        stream?.let { UsbDac.closeStream(it) }
        stream = null; playing = false
    }

    override fun release() { reset() }

    private companion object {
        val ENCODINGS = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
    }
}

/**
 * The sink of a player when the app's USB driver is on: songs that the DAC can take go to it, everything else (a rate it lacks, or D'JAI's mixer, which makes
 * its own sound) goes to Android's usual output. The choice is made each time the format changes.
 */
@UnstableApi
class UsbRoutingSink(private val fallback: AudioSink,
    /** Hears the player's offset of the stream: positions that it hands on are shifted by it. */
    private val onStreamOffset: (Long) -> Unit = {}) : AudioSink {
    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) { onStreamOffset(outputStreamOffsetUs); fallback.setOutputStreamOffsetUs(outputStreamOffsetUs); usb.setOutputStreamOffsetUs(outputStreamOffsetUs) }

    private val usb = UsbAudioSink()
    private var active: AudioSink = fallback
    private var listener: AudioSink.Listener? = null

    private fun useUsb(format: Format) = UsbDac.ready && !DjMix.routing && usb.accepts(format)

    /**
     * Android's output is never offered floating point or 24-bit samples: with them its sink skips the audio processors, and the taps of D'JAI and the spectrum
     * would hear nothing. The decoder then hands over 16 bits for Android, and the full depth only for the DAC.
     */
    private fun highResolution(format: Format) = MimeTypes.AUDIO_RAW == format.sampleMimeType && format.pcmEncoding != C.ENCODING_PCM_16BIT

    override fun setListener(listener: AudioSink.Listener) { this.listener = listener; fallback.setListener(listener); usb.setListener(listener) }
    override fun setPlayerId(playerId: PlayerId?) { fallback.setPlayerId(playerId); usb.setPlayerId(playerId) }
    override fun supportsFormat(format: Format): Boolean = useUsb(format) || (!highResolution(format) && fallback.supportsFormat(format))
    override fun getFormatSupport(format: Format): Int = when {
        useUsb(format) -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        highResolution(format) -> AudioSink.SINK_FORMAT_UNSUPPORTED
        else -> fallback.getFormatSupport(format)
    }
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = active.getCurrentPositionUs(sourceEnded)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        choose(inputFormat).configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun configure(config: AudioSink.AudioSinkConfig) { choose(config.format).configure(config) }

    private fun choose(format: Format): AudioSink {
        val next = if (useUsb(format)) usb else fallback
        if (next !== active) { active.reset(); active = next }
        return next
    }

    override fun play() = active.play()
    override fun pause() = active.pause()
    override fun handleDiscontinuity() = active.handleDiscontinuity()
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean = active.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    override fun playToEndOfStream() = active.playToEndOfStream()
    override fun isEnded(): Boolean = active.isEnded()
    override fun hasPendingData(): Boolean = active.hasPendingData()
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) { fallback.setPlaybackParameters(playbackParameters); usb.setPlaybackParameters(playbackParameters) }
    override fun getPlaybackParameters(): PlaybackParameters = active.playbackParameters
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) { fallback.skipSilenceEnabled = skipSilenceEnabled; usb.skipSilenceEnabled = skipSilenceEnabled }
    override fun getSkipSilenceEnabled(): Boolean = active.skipSilenceEnabled
    override fun setAudioAttributes(audioAttributes: AudioAttributes) { fallback.setAudioAttributes(audioAttributes); usb.setAudioAttributes(audioAttributes) }
    override fun getAudioAttributes(): AudioAttributes? = active.audioAttributes
    override fun setAudioSessionId(audioSessionId: Int) { fallback.setAudioSessionId(audioSessionId); usb.setAudioSessionId(audioSessionId) }
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) { fallback.setAuxEffectInfo(auxEffectInfo) }
    override fun getAudioTrackBufferSizeUs(): Long = active.audioTrackBufferSizeUs
    override fun enableTunnelingV21() = fallback.enableTunnelingV21()
    override fun disableTunneling() = fallback.disableTunneling()
    override fun setVolume(volume: Float) { fallback.setVolume(volume); usb.setVolume(volume) }
    override fun flush() = active.flush()
    override fun reset() { active.reset() }
    override fun release() { fallback.release(); usb.release() }
}
