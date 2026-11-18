package io.github.yyan115.opentune.playback.dj

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The sink of a hidden player that only prepares songs for D'JAI. Nothing is played: the decoded audio goes straight into the ring of deck [slot], and the
 * clock of the player is the audio handed over so far. The player then decodes as fast as the phone and the network allow, instead of at the pace of a
 * loudspeaker, even a fast-forwarded one.
 *
 * The analyzer must keep up: while more than [MAX_UNREAD_SECONDS] of the ring has not been read yet, the sink takes nothing more, and the player waits.
 */
@UnstableApi
class FastAnalysisSink(private val slot: Int) : AudioSink {
    private val writer = TapWriter(slot)
    private var sampleRate = 44_100
    private var configured = false
    private var playing = false
    private var ended = false
    private var startUs = C.TIME_UNSET
    private var frames = 0L
    private var parameters = PlaybackParameters.DEFAULT
    private var attributes = AudioAttributes.DEFAULT
    private var skipSilence = false

    override fun setListener(listener: AudioSink.Listener) {}

    private fun pcm(format: Format) = MimeTypes.AUDIO_RAW == format.sampleMimeType && format.pcmEncoding in TapWriter.SUPPORTED

    override fun supportsFormat(format: Format): Boolean = pcm(format)

    override fun getFormatSupport(format: Format): Int = if (pcm(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        if (startUs == C.TIME_UNSET) AudioSink.CURRENT_POSITION_NOT_SET else startUs + frames * 1_000_000L / sampleRate

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) = configure(inputFormat)

    override fun configure(config: AudioSink.AudioSinkConfig) = configure(config.format)

    private fun configure(format: Format) {
        if (!writer.configure(format.pcmEncoding, format.channelCount, format.sampleRate)) {
            throw AudioSink.ConfigurationException("D'JAI cannot read this format", format)
        }
        sampleRate = format.sampleRate
        configured = true
    }

    override fun play() { playing = true }

    override fun pause() { playing = false }

    override fun handleDiscontinuity() {}

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!configured) return false
        // Before the player starts, the sink takes a second of audio, as a real one fills its buffer: the player waits for that to be ready, and only then
        // starts. Taking nothing before the start would leave the two waiting for each other.
        if (!playing && frames >= sampleRate) return false
        val stream = DjMix.streams[slot]
        val ring = stream.ring
        if (ring != null && ring.written - stream.readMark > ring.rate.toLong() * MAX_UNREAD_SECONDS) return false
        if (startUs == C.TIME_UNSET) startUs = presentationTimeUs
        frames += writer.write(buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN))
        buffer.position(buffer.limit())
        return true
    }

    override fun playToEndOfStream() { ended = true }

    override fun isEnded(): Boolean = ended

    override fun hasPendingData(): Boolean = startUs != C.TIME_UNSET && !ended

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

    override fun setVolume(volume: Float) {}

    override fun flush() {
        startUs = C.TIME_UNSET; frames = 0L; ended = false
        writer.flush()
    }

    override fun reset() { flush(); configured = false; playing = false }

    private companion object {
        /** The most audio, in seconds, that the analyzer may have left to read before the sink waits for it. The ring holds [DjMix.RING_SECONDS]. */
        const val MAX_UNREAD_SECONDS = 30
    }
}
