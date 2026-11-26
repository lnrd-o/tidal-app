package io.github.lnrdo.mytidal.stems

import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import io.github.lnrdo.mytidal.OpenTuneApp
import io.github.lnrdo.mytidal.core.Track
import io.github.lnrdo.mytidal.core.dj.neural.StreamingResampler
import io.github.lnrdo.mytidal.core.stems.StereoSource
import io.github.lnrdo.mytidal.playback.OpenTuneSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A song at 44.1 kHz in two channels of 16-bit samples, held in memory while it is separated. */
class SongPcm(val left: ShortArray, val right: ShortArray) : StereoSource {
    override val length: Int get() = left.size
    override fun read(offset: Int, count: Int, left: FloatArray, right: FloatArray) {
        for (i in 0 until count) {
            val p = offset + i
            if (p < this.left.size) { left[i] = this.left[p] * (1f / 32768f); right[i] = this.right[p] * (1f / 32768f) } else { left[i] = 0f; right[i] = 0f }
        }
    }
}

/**
 * A song that is being read, which a separation can read at the same time: what is asked before it has arrived is waited for. Its length is the song's
 * [estimate] (from its duration) until it is whole, then the true one. A capture that fails ends it as [failed], and every wait returns.
 */
class LiveSong(private val estimate: Int) : StereoSource {
    private val lock = Object()
    private var left = ShortArray(1 shl 20); private var right = ShortArray(1 shl 20)
    private var size = 0
    private var finished = false
    @Volatile var failed = false
        private set

    override val length: Int get() = synchronized(lock) { if (finished) size else maxOf(size, estimate) }
    val whole: Boolean get() = synchronized(lock) { finished }

    fun append(l: ShortArray, r: ShortArray, n: Int) {
        if (n <= 0) return
        synchronized(lock) {
            if (finished) return
            if (size + n > left.size) { val grown = maxOf(left.size * 2, size + n); left = left.copyOf(grown); right = right.copyOf(grown) }
            System.arraycopy(l, 0, left, size, n); System.arraycopy(r, 0, right, size, n); size += n
            lock.notifyAll()
        }
    }

    fun finish(ok: Boolean) { synchronized(lock) { finished = true; failed = !ok; lock.notifyAll() } }

    override fun read(offset: Int, count: Int, left: FloatArray, right: FloatArray) {
        synchronized(lock) {
            while (!finished && size < offset + count) lock.wait(200)
            for (i in 0 until count) {
                val p = offset + i
                if (p < size) { left[i] = this.left[p] * (1f / 32768f); right[i] = this.right[p] * (1f / 32768f) } else { left[i] = 0f; right[i] = 0f }
            }
        }
    }

    fun toPcm(): SongPcm = synchronized(lock) { SongPcm(left.copyOf(size), right.copyOf(size)) }
}

/**
 * The sink of a hidden player that only captures a song. Like the sink that D'JAI reads songs with, it plays nothing and takes the decoded audio as fast
 * as it comes, but it keeps all of it: each channel is brought to 44.1 kHz by a windowed-sinc resampler (a song that already is at 44.1 kHz is only converted)
 * and held as 16-bit samples, which is what the separation network needs and half of what floats would take.
 */
@UnstableApi
internal class CaptureSink(private val live: LiveSong) : AudioSink {
    private var encoding = C.ENCODING_INVALID
    private var channels = 2
    private var rate = 44_100
    private var configured = false
    private var playing = false
    private var ended = false
    private var startUs = C.TIME_UNSET
    private var frames = 0L
    private var parameters = PlaybackParameters.DEFAULT
    private var attributes = AudioAttributes.DEFAULT
    private var skip = false
    private var resamplers: Array<StreamingResampler>? = null
    private var block = FloatArray(1 shl 14)
    private var shortsL = ShortArray(1 shl 13); private var shortsR = ShortArray(1 shl 13)
    /** How much of each resampler's output has been handed to [live]. */
    private var taken = 0
    private var startedAt = 0L

    /** Ends the capture: what the resamplers still hold goes to the song, which is then whole (or failed, when [ok] is false). */
    fun finish(ok: Boolean) {
        resamplers?.let { r -> r.forEach { it.finish() }; drain(r) }
        live.finish(ok)
    }

    /** Hands to the song what both resamplers have made since the last time. */
    private fun drain(r: Array<StreamingResampler>) {
        val end = minOf(r[0].outCount, r[1].outCount)
        val n = end - taken
        if (n <= 0) return
        if (shortsL.size < n) { shortsL = ShortArray(n); shortsR = ShortArray(n) }
        r[0].copyOut(taken, end, shortsL); r[1].copyOut(taken, end, shortsR)
        live.append(shortsL, shortsR, n)
        taken = end
    }

    override fun setListener(listener: AudioSink.Listener) {}
    // Floating point is not offered: a sink that says it takes it makes the player ask the decoder for it, and decoders that give 16-bit samples anyway
    // would then be read as floats, at half the length and as noise.
    private fun pcm(format: Format) = MimeTypes.AUDIO_RAW == format.sampleMimeType && format.pcmEncoding in SUPPORTED && format.pcmEncoding != C.ENCODING_PCM_FLOAT
    override fun supportsFormat(format: Format) = pcm(format)
    override fun getFormatSupport(format: Format) = if (pcm(format)) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long = if (startUs == C.TIME_UNSET) AudioSink.CURRENT_POSITION_NOT_SET else startUs + frames * 1_000_000L / rate
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) = configure(inputFormat)
    override fun configure(config: AudioSink.AudioSinkConfig) = configure(config.format)

    private fun configure(format: Format) {
        if (format.pcmEncoding !in SUPPORTED) throw AudioSink.ConfigurationException("The stems cannot read this format", format)
        encoding = format.pcmEncoding; channels = format.channelCount.coerceAtLeast(1)
        if (!configured) {
            rate = format.sampleRate
            if (rate != 44_100) resamplers = arrayOf(StreamingResampler(rate, 44_100), StreamingResampler(rate, 44_100))
        }
        configured = true
    }

    override fun play() { playing = true }
    override fun pause() { playing = false }
    override fun handleDiscontinuity() {}

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!configured) return false
        if (!playing && frames >= rate) return false
        // Not faster than [MAX_SPEED] times the music, as the sink that D'JAI reads songs with: a reader that takes everything at once leaves the player at
        // the end of its buffer, where it stalls. The player asks again a few milliseconds later.
        if (startUs != C.TIME_UNSET) {
            if (frames.toDouble() / rate > (System.nanoTime() - startedAt) / 1e9 * MAX_SPEED + 2.0) return false
        } else startedAt = System.nanoTime()
        if (startUs == C.TIME_UNSET) startUs = presentationTimeUs
        val bytes = when (encoding) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_24BIT -> 3; else -> 4 }
        val frame = bytes * channels
        val n = buffer.remaining() / frame
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var at = b.position()
        var done = 0
        while (done < n) {
            val take = minOf(n - done, block.size / 2)
            for (f in 0 until take) {
                val l = sample(b, at); val r = if (channels > 1) sample(b, at + bytes) else l
                block[f] = l; block[take + f] = r
                at += frame
            }
            val resample = resamplers
            if (resample == null) {
                if (shortsL.size < take) { shortsL = ShortArray(take); shortsR = ShortArray(take) }
                for (f in 0 until take) {
                    shortsL[f] = (block[f] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort(); shortsR[f] = (block[take + f] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                }
                live.append(shortsL, shortsR, take)
            } else {
                resample[0].feed(block.copyOfRange(0, take), take); resample[1].feed(block.copyOfRange(take, 2 * take), take)
                drain(resample)
            }
            done += take
        }
        buffer.position(buffer.limit())
        frames += n
        return true
    }

    private fun sample(b: ByteBuffer, at: Int): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> b.getShort(at) * (1f / 32768f)
        C.ENCODING_PCM_24BIT -> ((b.get(at + 2).toInt() shl 16) or ((b.get(at + 1).toInt() and 0xFF) shl 8) or (b.get(at).toInt() and 0xFF)) * (1f / 8388608f)
        C.ENCODING_PCM_32BIT -> b.getInt(at) * (1f / 2147483648f)
        else -> b.getFloat(at)
    }

    override fun playToEndOfStream() { ended = true }
    override fun isEnded() = ended
    override fun hasPendingData() = startUs != C.TIME_UNSET && !ended
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) { parameters = playbackParameters }
    override fun getPlaybackParameters() = parameters
    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) { skip = skipSilenceEnabled }
    override fun getSkipSilenceEnabled() = skip
    override fun setAudioAttributes(audioAttributes: AudioAttributes) { attributes = audioAttributes }
    override fun getAudioAttributes() = attributes
    override fun setAudioSessionId(audioSessionId: Int) {}
    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}
    override fun getAudioTrackBufferSizeUs() = C.TIME_UNSET
    override fun enableTunnelingV21() {}
    override fun disableTunneling() {}
    override fun setVolume(volume: Float) {}
    override fun flush() { startUs = C.TIME_UNSET; frames = 0; ended = false }
    // The player resets its sink when the format of the stream changes, and plays on: the sink must keep that it is playing, as a real one does, and what it
    // has captured. Only a new capture starts from nothing.
    override fun reset() { flush(); configured = false }

    private companion object {
        val SUPPORTED = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
        /** The fastest that a song is read, in times its length. */
        const val MAX_SPEED = 16.0
    }
}

/** A growing array of shorts, without boxing. */
internal class ShortArrayList {
    private var data = ShortArray(1 shl 20)
    var size = 0
        private set
    fun add(v: Short) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
    fun toArray() = data.copyOf(size)
}

/** Reads whole songs, much faster than they play, through a hidden player that nobody hears. */
@UnstableApi
object SongCapture {
    /** The longest song that is read: its 16-bit stereo copy takes about 10 MB for each minute. */
    const val MAX_MINUTES = 12

    /** The length of [track] at 44.1 kHz as its duration gives it, for a [LiveSong] that is not whole yet. */
    fun estimate(track: Track): Int = (track.durationMs.coerceAtLeast(0) * 44_100 / 1000).toInt()

    /**
     * Reads [track] and returns it at 44.1 kHz, or null when it could not be read in a reasonable time. [progress] goes from 0 to 1 as the song is read;
     * [cancelled] is asked while it waits.
     */
    suspend fun read(app: OpenTuneApp, track: Track, progress: (Float) -> Unit, cancelled: () -> Boolean): SongPcm? {
        val live = LiveSong(estimate(track))
        return if (capture(app, track, live, progress, cancelled)) live.toPcm() else null
    }

    /**
     * Reads [track] into [live], which a separation may read at the same time, and returns whether it was read whole. [live] is ended either way, as failed
     * when it was not.
     */
    suspend fun capture(app: OpenTuneApp, track: Track, live: LiveSong, progress: (Float) -> Unit, cancelled: () -> Boolean): Boolean {
        val sink = CaptureSink(live)
        var ok = false
        try { ok = play(app, track, sink, progress, cancelled); return ok } finally { sink.finish(ok) }
    }

    private suspend fun play(app: OpenTuneApp, track: Track, sink: CaptureSink, progress: (Float) -> Unit, cancelled: () -> Boolean): Boolean {
        val item = app.sources.resolve(track)
        val renderers = object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink = sink
        }
        val player = withContext(Dispatchers.Main.immediate) {
            ExoPlayer.Builder(app, renderers).setMediaSourceFactory(OpenTuneSourceFactory(app))
                // The load control that the app's own players use: a longer buffer than the default, which a reader that races ahead would run into.
                .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(40_000, 90_000, 1_000, 3_000)
                    .setTargetBufferBytes(64 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true).build())
                .build().also {
                it.addListener(object : Player.Listener {
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { android.util.Log.w("OpenTuneStems", "player error ${error.errorCodeName}", error) }
                })
                it.volume = 0f; it.setPlaybackParameters(PlaybackParameters(4f)); it.setMediaItem(item); it.playWhenReady = true; it.prepare()
            }
        }
        try {
            val limit = 60_000L + (if (track.durationMs > 0) track.durationMs else 600_000L)
            val began = System.currentTimeMillis()
            while (true) {
                val state = withContext(Dispatchers.Main.immediate) {
                    val duration = player.duration.takeIf { it > 0 } ?: track.durationMs
                    if (duration > 0) progress((player.currentPosition.toFloat() / duration).coerceIn(0f, 1f))
                    if (player.playerError != null) -1 else player.playbackState
                }
                if (state == -1) { android.util.Log.w("OpenTuneStems", "the song could not be read", withContext(Dispatchers.Main.immediate) { player.playerError?.cause ?: player.playerError }); return false }
                if (state == Player.STATE_ENDED) break
                if (cancelled()) { android.util.Log.w("OpenTuneStems", "reading was cancelled"); return false }
                if (System.currentTimeMillis() - began > limit) { android.util.Log.w("OpenTuneStems", "reading took too long"); return false }
                delay(200)
            }
            return true
        } finally {
            withContext(Dispatchers.Main.immediate) { runCatching { player.release() } }
        }
    }
}
