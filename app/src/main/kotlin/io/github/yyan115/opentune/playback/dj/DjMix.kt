package io.github.yyan115.opentune.playback.dj

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import io.github.yyan115.opentune.core.dj.DjMixer
import io.github.yyan115.opentune.core.dj.MixKind
import io.github.yyan115.opentune.core.dj.PcmRing
import io.github.yyan115.opentune.core.dj.RingSource
import io.github.yyan115.opentune.playback.AudioSpectrum
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where D'JAI is in the life of one transition. */
enum class DjPhase { OFF, WAITING, READING, PLANNED, MIXING }

/** What the interface shows about the mix. */
data class DjUi(
    val phase: DjPhase = DjPhase.OFF,
    val kind: MixKind? = null,
    /** The beats of the whole transition, the beats of the blend before the swap, and the fraction of the length at which the swap falls. */
    val beats: Int = 48,
    val blendBeats: Int = 32,
    val swap: Double = 2.0 / 3.0,
    val bpmOut: Double? = null,
    val bpmIn: Double? = null,
    val keyOut: String? = null,
    val keyIn: String? = null,
    val keyScore: Double = 0.5,
    /** The energy of the two tracks on a scale of 1 to 10, and how well the pair goes together from 0 to 1. */
    val energyOut: Double? = null,
    val energyIn: Double? = null,
    val match: Double? = null,
    /** The tempo factor applied to the incoming track. */
    val speed: Double = 1.0,
    /** Milliseconds until the transition starts. Zero while it runs. */
    val startsInMs: Long = 0,
    /** Length of the transition in milliseconds. */
    val lengthMs: Long = 0,
    val nextTitle: String? = null,
    /** True while an album plays. D'JAI is then off for the queue. */
    val album: Boolean = false,
    /** The title of the song that D'JAI moved to the next place because it matches better, and how well it matches. */
    val changed: String? = null,
    val changedMatch: Double? = null,
    /** How many of the next songs were listened to, out of how many. */
    val scouted: Int = 0,
    val scoutTotal: Int = 0,
    /** What the planned or running transition does besides the mix, for example ROLL and DROP. Empty when nothing. */
    val effects: String = "",
)

/**
 * The state that the decks, the mixer thread, the engine and the screens share. The decks write PCM into [streams],
 * the mixer reads them and plays the result through its own audio track, and the screens read [ui] and the live gains.
 */
object DjMix {
    /** The audio that one deck decoded. The ring is replaced when the sample rate of the track changes. */
    class Stream : RingSource {
        @Volatile var ring: PcmRing? = null
        override fun ring(): PcmRing? = ring

        /** Makes sure that a ring of the given rate exists. A new one starts empty. */
        fun ensure(rate: Int): PcmRing {
            val existing = ring
            if (existing != null && existing.rate == rate) return existing
            return PcmRing(rate * RING_SECONDS, rate).also { ring = it }
        }

        fun clear() { ring = null }
    }

    const val RING_SECONDS = 50
    const val OUT_RATE = 48_000

    /** The two decks of the mixer and, last, the hidden deck that prepares songs ([ANALYSIS_SLOT]). */
    val streams = arrayOf(Stream(), Stream(), Stream())
    const val ANALYSIS_SLOT = 2

    /** What D'JAI has kept about a song: its tempo, key and energy when known, and whether its cue points are kept. */
    class SongInfo(val bpm: Double?, val key: String?, val energy: Double?, val cues: Boolean)

    /** The listener asked D'JAI to forget what it kept about songs. The service reads it. */
    val forgetRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The songs that D'JAI has kept something about, by track id. The lists read it to mark them. */
    val known = kotlinx.coroutines.flow.MutableStateFlow<Map<String, SongInfo>>(emptyMap())

    /** Songs that the listener asked to prepare, and what came of them. The screen sends the first and reads the second. */
    val preprocess = kotlinx.coroutines.flow.MutableSharedFlow<PreprocessRequest>(extraBufferCapacity = 64)
    val preprocessResults = kotlinx.coroutines.flow.MutableSharedFlow<PreprocessResult>(extraBufferCapacity = 64)
    val mixer = DjMixer(OUT_RATE)

    /** True while the mixer plays instead of the players: the decks then decode into the rings and output silence. */
    @Volatile var routing = false
    /** The deck whose player the media session shows. */
    @Volatile var sessionSlot = 0

    private val mutableUi = MutableStateFlow(DjUi())
    val ui: StateFlow<DjUi> = mutableUi.asStateFlow()
    fun publish(next: DjUi) { if (next != mutableUi.value) mutableUi.value = next }
}

/** Plays what the mixer renders through an audio track of its own. */
class DjOutput {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "djai-mixer").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val rate = DjMix.OUT_RATE
        val chunk = 480
        val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(minimum, chunk * 2 * 4 * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val buffer = FloatArray(chunk * 2)
        try {
            track.play()
            while (running) {
                DjMix.mixer.render(buffer, chunk)
                if (AudioSpectrum.enabled) for (i in 0 until chunk) AudioSpectrum.write((buffer[i * 2] + buffer[i * 2 + 1]) * 0.5f, rate)
                if (track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING) < 0) break
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }
}
