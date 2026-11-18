package io.github.yyan115.opentune.playback.dj

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.yyan115.opentune.core.dj.Cue
import io.github.yyan115.opentune.core.dj.TrackSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A song that the listener asked D'JAI to prepare: [id] is its track id, and [item] the media item that plays it. */
class PreprocessRequest(val id: String, val title: String, val item: MediaItem, val durationMs: Long)

/** What came of a [PreprocessRequest]. [ok] is false when the song could not be read. */
class PreprocessResult(val title: String, val ok: Boolean, val bpm: Double?, val key: String?, val cues: List<Cue>)

/**
 * Prepares a song for D'JAI without playing it: a hidden player reads the whole song at four times its speed, through the same tap and analyzer
 * as a song that plays, and what comes out is its tempo, its key, its energy and its cue points. The songs are done one after another on a deck of their
 * own, so this works whether or not D'JAI is on and does not touch what is playing.
 */
@UnstableApi
class Preprocessor(
    private val scope: CoroutineScope,
    /** Builds the hidden player of an analysis slot. */
    private val build: (Int) -> ExoPlayer,
    private val learn: (String, TrackSummary, Int) -> Unit,
    private val saveCues: (String, List<Cue>) -> Unit,
    /** Whether D'JAI is on. While it is off nothing is prepared: a song that is being read is put back, and the queue waits. */
    private val enabled: () -> Boolean = { true },
    /** The timed lyrics of a song, by its id, or null. */
    private val lyrics: suspend (String) -> List<io.github.yyan115.opentune.core.LyricLine>? = { null },
    /** How many songs are prepared at once, each by its own player and analyzer, from 1 to [DjMix.MAX_WORKERS]. */
    private val workers: () -> Int = { 1 },
) {
    private val queue = Channel<PreprocessRequest>(Channel.UNLIMITED)

    init {
        // One worker for each slot. A worker past the number that the listener chose waits, so that the setting acts at once.
        for (w in 0 until DjMix.MAX_WORKERS) scope.launch {
            while (true) {
                if (w >= workers().coerceIn(1, DjMix.MAX_WORKERS)) { delay(1_000); continue }
                val request = queue.receive()
                var result: PreprocessResult? = null
                while (result == null) {
                    while (!enabled()) delay(1_000)
                    result = run(request, DjMix.ANALYSIS_SLOT + w)
                }
                DjMix.preprocessResults.tryEmit(result)
            }
        }
    }

    fun add(request: PreprocessRequest) { queue.trySend(request) }

    /** Reads the song and returns what came of it, or null when D'JAI was turned off on the way: the song is then read again later. */
    private suspend fun run(request: PreprocessRequest, slot: Int): PreprocessResult? {
        val failed = PreprocessResult(request.title, false, null, null, emptyList())
        val analyzer = DeckAnalyzer(slot, scope, { 0L }, estimateEvery = ESTIMATE_SECONDS)
        analyzer.hintBpm = catalogBpm(request.item)
        DjMix.streams[slot].clear()
        val player = runCatching { build(slot) }.getOrNull() ?: return failed
        try {
            player.volume = 0f
            player.setPlaybackParameters(PlaybackParameters(SPEED))
            player.setMediaItem(request.item)
            player.playWhenReady = true
            player.prepare()
            analyzer.start()
            val started = SystemClock.elapsedRealtime()
            // A song is read in a quarter of its length or less. Twice that, and some seconds, is the most that it may take.
            val limit = 45_000L + (if (request.durationMs > 0) request.durationMs else 600_000L) / 2
            while (player.playbackState != Player.STATE_ENDED && player.playerError == null && SystemClock.elapsedRealtime() - started < limit) {
                if (!enabled()) return null
                delay(250)
            }
            if (player.playbackState != Player.STATE_ENDED) return failed
            // The analyzer is still catching up with the end of the ring: it makes one more estimate when it has.
            val before = analyzer.estimates
            analyzer.requestEstimate()
            val waited = SystemClock.elapsedRealtime()
            while (analyzer.estimates == before && SystemClock.elapsedRealtime() - waited < 4_000L) delay(100)
            val duration = if (request.durationMs > 0) request.durationMs else player.duration.coerceAtLeast(0L)
            val beat = analyzer.beat
            val key = analyzer.key
            val cues = analyzer.sectionInput(duration)?.let { input -> detectCues(input, runCatching { lyrics(request.id) }.getOrNull()) }.orEmpty()
            val summary = TrackSummary(beat, key?.camelot, analyzer.energyIntro ?: analyzer.energyRecent, analyzer.origin, swing = analyzer.swing)
            if (beat == null && key == null) return failed
            val took = SystemClock.elapsedRealtime() - started
            android.util.Log.i("OpenTune", "D'JAI prepared %s on slot %d: %.0f s of music in %.1f s (%.1f times its speed)".format(request.title, slot, duration / 1000.0, took / 1000.0,
                if (took > 0) duration.toDouble() / took else 0.0))
            learn(request.id, summary, analyzer.rate)
            if (cues.isNotEmpty()) saveCues(request.id, cues)
            return PreprocessResult(request.title, true, beat?.bpm, key?.camelot, cues)
        } finally {
            analyzer.stop()
            runCatching { player.release() }
            DjMix.streams[slot].clear()
        }
    }

    private companion object {
        /** The speed of the hidden player. Fast enough to be quick, slow enough for the analyzer to keep up with it. */
        const val SPEED = 4f
        /** The analyzer makes a full estimate every so many seconds of the song, and not every two, so that it keeps up with the speed. */
        const val ESTIMATE_SECONDS = 20f
    }
}
