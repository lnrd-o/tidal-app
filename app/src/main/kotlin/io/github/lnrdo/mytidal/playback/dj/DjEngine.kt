package io.github.lnrdo.mytidal.playback.dj

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.lnrdo.mytidal.core.dj.BeatAnalysis
import io.github.lnrdo.mytidal.core.dj.Cue
import io.github.lnrdo.mytidal.core.dj.CueKind
import io.github.lnrdo.mytidal.core.dj.CueMark
import io.github.lnrdo.mytidal.core.dj.EnergyArc
import io.github.lnrdo.mytidal.core.dj.Candidate
import io.github.lnrdo.mytidal.core.dj.KickAligner
import io.github.lnrdo.mytidal.core.dj.PcmRing
import io.github.lnrdo.mytidal.core.dj.MixDecision
import io.github.lnrdo.mytidal.core.dj.MixKind
import io.github.lnrdo.mytidal.core.dj.MixPlan
import io.github.lnrdo.mytidal.core.dj.MixPlanner
import io.github.lnrdo.mytidal.core.dj.MixScheduler
import io.github.lnrdo.mytidal.core.dj.TrackSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The file in which what the listening ahead learned about songs is kept between sessions, in the files folder. */
const val DJAI_PROFILE_FILE = "djai_profiles.tsv"

/** The tempo, the key and the family of style of a song that an online catalogue gave, so that it can be scored before it is listened to. */
class OnlineHint(val bpm: Double?, val camelot: String?, val genre: io.github.lnrdo.mytidal.core.dj.GenreFamily? = null)

/** What the listener chose about how D'JAI works: its effects, how it chooses the next song, and how far ahead it listens. */
class DjOptions(
    val roll: Boolean = true, val cues: Boolean = true, val echo: Boolean = true, val filters: Boolean = true,
    val groove: Boolean = true, val arc: Boolean = true,
    val minMatch: Double = io.github.lnrdo.mytidal.core.dj.MixPlanner.MIN_MATCH,
    val listenAhead: Int = 12,
    val revalidate: Boolean = true,
    val beatFirst: Boolean = true,
    /** The rhythm model reads the songs ahead whole. */
    val neural: Boolean = false,
)

/** The tempo that TIDAL gave for the song of a media item, in its data, or null. */
fun catalogBpm(item: MediaItem?): Double? =
    item?.mediaMetadata?.extras?.getString("track")?.let { runCatching { io.github.lnrdo.mytidal.core.JsonCodec.decodeTrack(org.json.JSONObject(it)).bpm }.getOrNull() }

/** What the engine needs from the playback service. */
@UnstableApi
interface DjHost {
    val player: ExoPlayer
    /** Builds a player with the same settings as the main one, bound to the deck [slot] and without audio focus. */
    fun buildDeck(slot: Int): ExoPlayer
    /** Builds a hidden player that only feeds the ring of [slot]: nothing of it is heard, and it decodes as fast as the settings allow. */
    fun buildAnalysis(slot: Int): ExoPlayer
    /** Makes [next] the player of the media session. */
    fun adopt(next: ExoPlayer)
    fun gainFor(item: MediaItem?): Float
    fun titleOf(item: MediaItem?): String?
    val sleepAtTrackEnd: Boolean
    /** True while the queue is one album, which plays as released. */
    fun albumQueue(): Boolean
    /** The file in which what the listening ahead found out about songs is kept between sessions. */
    fun profileFile(): java.io.File
    /** The cue points that were found in the song with [id] on an earlier playthrough, in milliseconds. */
    fun cuesOf(id: String): List<Cue>
    /** Keeps the cue points that were found in the song with [id]. */
    fun saveCues(id: String, cues: List<Cue>)
    /** The timed lyrics of the song with [id], from the device or, when it is allowed, from TIDAL, or null when it has none. */
    suspend fun lyricsOf(id: String): List<io.github.lnrdo.mytidal.core.LyricLine>?
    /** Keeps the song of [item] in the library, so that lists that D'JAI builds can show it. */
    fun rememberTrack(item: MediaItem)
    /** The tempo and key of [item] from an online catalogue, or null. It may take a network request. */
    suspend fun onlineHint(item: MediaItem): OnlineHint?
}

/**
 * Runs the automix. While the track plays, the engine waits for the last minute of it, then starts the next track on a second deck
 * that decodes silently into a ring. It reads the tempo and the key of both tracks from their rings, plans the transition on the beat grids,
 * gives the plan to the mixer, and at the bass swap makes the second deck's player the one of the media session. When the mix ends,
 * the first deck is released.
 *
 * Any seek, skip or change of the queue while a transition is prepared or running cancels it, and the track plays on normally.
 */
@UnstableApi
class DjEngine(private val host: DjHost, private val scope: CoroutineScope) {
    private enum class State { OFF, IDLE, PREPARING, CUEING, READY, MIXING }

    private val output = DjOutput()
    private val analyzers = arrayOf(DeckAnalyzer(0, scope, songMs = { songPosition(0) }), DeckAnalyzer(1, scope, songMs = { songPosition(1) }))
    /** The analyzer of the hidden deck that listens ahead. */
    private val scoutAnalyzer = DeckAnalyzer(DjMix.SCOUT_SLOT, scope)

    /**
     * The position of the song of a deck in milliseconds, as the last tick saw it. A player may only be asked on the main thread, and the analyzers
     * ask from their own, so the main thread keeps the numbers here.
     */
    private val positions = LongArray(2) { -1L }
    private fun songPosition(slot: Int): Long = positions[slot]

    /** Reads the players, on the main thread. The session player is the deck of the session slot, and the other deck is the one that is prepared or listened to. */
    private fun readPositions() {
        val session = DjMix.sessionSlot
        positions[session] = host.player.currentPosition
        positions[1 - session] = (incoming ?: scout)?.currentPosition ?: -1L
    }

    /** Whether D'JAI uses loop rolls and cue points inside its transitions. */
    @Volatile private var options = DjOptions()
    /** Where the incoming song began, in milliseconds, and its cue points, for the transition that is prepared. */
    private var incomingStartMs = 0L
    private var incomingCues: List<Cue> = emptyList()
    /** What the plan of this transition does that is more than a mix, for the strip. */
    private var planEffects = ""
    /**
     * The cue of the incoming song. After the plan, the incoming player is sent back to a little before the point where the mix will enter it ([cueMs]) and
     * held there, paused, until just before the mix: then it plays from there, and the song that the mixer reads is the song that the player plays. Without it
     * the incoming player would run on from its preparation, tens of seconds ahead of what is heard, and the mix would read an old part of the ring, or one that
     * was already overwritten, and the next mix would be timed by a player that was ahead of the music.
     */
    private var incomingHeld = false
    private var pending: MixPlan? = null
    private var enterMs = 0.0
    private var cueMs = 0L
    private var cueDiscontinuity = 0L
    private var cueSince = 0L
    private var placedDiscontinuity = 0L
    /** What the plan knew of the incoming song, for the strip while its analyzer starts again after the cue. */
    private var planBpmIn: Double? = null
    private var planKeyIn: String? = null
    /** The match that the choice saw for the song that it left in the next place, by song, and the song for which another has been chosen already. */
    private val choiceMatch = HashMap<String, Double>()
    private var rechosenFor: String? = null
    /** The incoming song whose match for the mix was logged last. */
    private var checkedFor: String? = null
    /** After a change by hand the whole queue is checked again: since when, and with how many songs known at the last check. */
    private var revalidating = false
    private var revalidateSince = 0L
    private var revalidatedWith = -1
    /** The energies of the songs that played last, the oldest first, for the arc of the set. */
    private val played = ArrayDeque<Double>()
    private var state = State.OFF
    private var tick: Job? = null

    private var outgoing: ExoPlayer? = null
    private var incoming: ExoPlayer? = null
    private var outSlot = 0
    private var inSlot = 1
    private var plan: MixPlan? = null
    private var decision: MixDecision? = null
    private var swapped = false
    private var finishedBase = 0
    private var itemId: String? = null
    private var attempted: String? = null
    private var nextTitle: String? = null
    /** What the listening ahead found out about a track that is not playing yet. */
    private class Profile(val summary: TrackSummary, val rate: Int)
    private val profiles = LinkedHashMap<String, Profile>().also { loadProfiles(it) }
    /** What online catalogues said about the songs ahead, and the songs that were already asked about. */
    private val online = HashMap<String, OnlineHint>()
    private val asked = HashSet<String>()
    private var scout: ExoPlayer? = null
    private var scoutId: String? = null
    private var scoutSince = 0L
    private var scouted = 0
    private var scoutTotal = 0
    /** The item that the next track was already chosen for, and what the choice did, for the screen. */
    private var chosenFor: String? = null
    private var changedTitle: String? = null
    private var changedMatch: Double? = null
    /** True while an album plays: D'JAI keeps out of the way, and the players use their own audio path. */
    private var suspended = false
    /** True when D'JAI mixes. When it is off, the engine only crossfades for [crossfadeMs], and analyses nothing. */
    @Volatile private var djai = true
    @Volatile private var crossfadeMs = 0
    /** True when Tone Lab colours the sound. The mixer then also plays albums, which get no transitions. */
    @Volatile private var elzar = false
    private var albumNow = false

    /**
     * Watches the song that has taken over, until the transition has ended. The watcher of the outgoing song is gone at the swap, so without this a skip, a seek
     * or a new queue in the tail of a mix left the mixer running the old plan over music that had changed.
     */
    private val tailWatcher = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (swapped && (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SKIP || reason == Player.DISCONTINUITY_REASON_REMOVE)) userChangedSong()
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (swapped && (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)) userChangedSong()
        }
    }

    private val watcher = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { if (!swapped) abort("discontinuity $reason") }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { if (!swapped) abort("transition $reason") }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED || swapped) return
            // Songs added to the queue, or moved after the next one, do not touch the transition that is prepared: they are carried over to the incoming deck at
            // the swap. Only a change to the song that plays or to the one that comes in drops it.
            val a = outgoing ?: return
            val b = incoming ?: return
            val stillSo = a.currentMediaItem?.mediaId == itemId && a.nextMediaItemIndex != C.INDEX_UNSET && a.getMediaItemAt(a.nextMediaItemIndex).mediaId == b.currentMediaItem?.mediaId
            if (!stillSo) abort("queue changed")
        }
    }
    private val mirrorOut = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!incomingHeld) incoming?.let { if (it.playWhenReady != playWhenReady) it.playWhenReady = playWhenReady } }
    }
    private val mirrorIn = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!incomingHeld) outgoing?.let { if (it.playWhenReady != playWhenReady) it.playWhenReady = playWhenReady } }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) { if (!swapped) abort("deck error") }
    }

    /** A new track started on the session player without a flush (a gapless change). Its analysis starts again from just before now. */
    /**
     * The listener changed the song by hand (skip, jump, a new queue) or moved inside it. Everything that D'JAI had worked out for the song that was playing is
     * dropped, and it starts again for the song that plays now: a transition that was prepared or running goes, the listening ahead ends and counts from nothing,
     * the choice of the next song and what the strip showed about it are forgotten, and the analysis of the song begins again. What was learned about songs stays.
     */
    fun userChangedSong() {
        if (state == State.OFF) return
        if (state != State.IDLE) abort("changed by hand")
        endScout()
        chosenFor = null; attempted = null; rechosenFor = null; checkedFor = null
        scouted = 0; scoutTotal = 0; changedTitle = null; changedMatch = null; planEffects = ""
        asked.clear()
        // The song that was asked to play next has played, or has been replaced: the pin goes.
        if (host.player.currentMediaItem?.mediaId == DjMix.pinnedNext) DjMix.pinnedNext = null
        revalidating = options.revalidate && djai
        revalidateSince = System.currentTimeMillis(); revalidatedWith = -1
        if (djai) {
            val slot = DjMix.sessionSlot
            analyzers[slot].hintBpm = hintFor(host.player.currentMediaItem)
            DjMix.streams[slot].ring?.let { analyzers[slot].restart(maxOf(it.discontinuity, it.written - (0.3 * it.rate).toLong())) }
        }
        publish()
    }

    /** The listener moved inside the song: a transition that was planned for the old position is dropped, and the planning is open again. */
    fun userSeeked() {
        if (state == State.OFF) return
        if (state != State.IDLE) abort("seek")
        attempted = null; rechosenFor = null; checkedFor = null
        publish()
    }

    fun onTrackChanged() {
        if (host.player.currentMediaItem?.mediaId == DjMix.pinnedNext) DjMix.pinnedNext = null
        if (state != State.IDLE) return
        val slot = DjMix.sessionSlot
        analyzers[slot].hintBpm = hintFor(host.player.currentMediaItem)
        DjMix.streams[slot].ring?.let { analyzers[slot].restart(maxOf(it.discontinuity, it.written - (0.3 * it.rate).toLong())) }
    }

    /** Sets what the engine does: D'JAI automix, or only a crossfade of [crossfadeSeconds]. Call it before [setEnabled]. */
    fun configure(djai: Boolean, crossfadeSeconds: Int, elzar: Boolean = false, options: DjOptions = DjOptions()) {
        val wasDjai = this.djai
        this.options = options
        DjMix.mixer.filters = options.filters
        this.elzar = elzar
        this.djai = djai; crossfadeMs = crossfadeSeconds * 1000
        if (state == State.OFF || wasDjai == djai) return
        if (!djai) {
            // With D'JAI off nothing of it runs in the background: a transition that is prepared is dropped, the listening ahead ends, the analyzers stop,
            // and what the strip showed about the next song goes away. Songs that were learned stay learned.
            if (state == State.PREPARING || state == State.READY) abort("D'JAI off")
            endScout()
            analyzers.forEach { it.stop() }
            scouted = 0; scoutTotal = 0; changedTitle = null; changedMatch = null
        } else if (!suspended) {
            // With D'JAI on again everything starts again for the song that plays now: its analysis, the listening ahead and the questions to the catalogue.
            chosenFor = null; attempted = null; asked.clear(); online.clear()
            analyzers[DjMix.sessionSlot].start()
        }
    }

    fun setEnabled(on: Boolean) {
        if (on == (state != State.OFF)) return
        if (on) enable() else disable()
    }

    private fun enable() {
        suspended = host.albumQueue() && !elzar
        if (!suspended) startRouting()
        state = State.IDLE
        tick = scope.launch { while (isActive) { runCatching { step() }.onFailure { android.util.Log.w("OpenTune", "D'JAI step failed", it); abort("error") }; delay(TICK_MS) } }
    }

    private fun startRouting() {
        val slot = DjMix.sessionSlot
        for (deck in 0..1) DjMix.streams[deck].clear()
        DjMix.mixer.cancel(slot)
        DjMix.mixer.setSource(slot, DjMix.streams[slot])
        DjMix.mixer.setSource(1 - slot, null)
        DjMix.mixer.setGain(slot, host.gainFor(host.player.currentMediaItem))
        DjMix.routing = true
        analyzers[slot].hintBpm = hintFor(host.player.currentMediaItem)
        if (djai) analyzers[slot].start()
        output.start()
    }

    private fun stopRouting() {
        endScout()
        DjMix.routing = false
        output.stop()
        analyzers.forEach { it.stop() }
        DjMix.mixer.setSource(0, null); DjMix.mixer.setSource(1, null)
        for (deck in 0..1) DjMix.streams[deck].clear()
    }

    private fun disable() {
        abort("off")
        tick?.cancel(); tick = null
        stopRouting()
        suspended = false
        state = State.OFF
        DjMix.publish(DjUi())
    }

    private fun step() {
        readPositions()
        // A paused player holds the mixer too: it must not play on from what the ring still holds.
        DjMix.mixer.setHold(!host.player.playWhenReady)
        // An album plays as released: the mixer stops and the player uses its own audio path until the queue is mixed again.
        val album = host.albumQueue()
        albumNow = album
        // An album plays untouched, unless Tone Lab colours it: then the mixer plays it too, but without any transition.
        val untouched = album && !elzar
        if (untouched != suspended) { suspended = untouched; if (untouched) { abort("album"); stopRouting() } else startRouting() }
        if (suspended) { publish(); return }
        if (album) { publish(); return }
        when (state) {
            State.IDLE -> idle()
            State.PREPARING -> preparing()
            State.CUEING -> cueing()
            State.READY -> ready()
            State.MIXING -> mixing()
            State.OFF -> Unit
        }
        publish()
    }

    // The track plays. While it does, the next songs of the queue are listened to one after the other. A little before the last minute the
    // best match among them is moved to the next place, and then the next track is started on the other deck.
    private fun idle() {
        val a = host.player
        if (!a.isPlaying) return
        val duration = a.duration
        if (duration == C.TIME_UNSET || duration < (if (djai) MIN_TRACK_MS else 20_000L)) return
        val next = a.nextMediaItemIndex
        if (next == C.INDEX_UNSET || a.repeatMode == Player.REPEAT_MODE_ONE || host.sleepAtTrackEnd) { endScout(); return }
        val id = a.currentMediaItem?.mediaId
        if (id == null || id == attempted) return
        val remaining = duration - a.currentPosition
        if (!djai) {
            // Crossfade only: the next track starts a few seconds before the blend, and nothing is analysed.
            if (crossfadeMs > 0 && remaining <= crossfadeMs + CROSSFADE_PREROLL_MS && remaining >= crossfadeMs + 2_000L) startPreroll(a, next, id)
            return
        }
        if (revalidating) revalidate(a, remaining)
        // Listening goes on until the choice is due, unless every song ahead is already known: then the choice is made at once.
        val everythingKnown = scout == null && scoutTotal > 0 && scouted >= scoutTotal
        if (remaining > PREROLL_MS + CHOOSE_MS && !everythingKnown) { scouting(a, id); return }
        endScout()
        if (remaining < MIN_REMAINING_MS) return
        if (chosenFor != id) { chosenFor = id; chooseNext(a) }
        if (remaining > PREROLL_MS) return
        startPreroll(a, a.nextMediaItemIndex, id)
    }

    /** The indexes of the songs that come after the current one, in the order in which they will play, with shuffle and repeat. */
    private fun upcoming(a: ExoPlayer, limit: Int = if (revalidating) maxOf(options.listenAhead, REVALIDATE_AHEAD) else options.listenAhead): List<Int> {
        val timeline = a.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val list = ArrayList<Int>()
        var i = a.currentMediaItemIndex
        val first = i
        while (list.size < limit) {
            i = timeline.getNextWindowIndex(i, a.repeatMode, a.shuffleModeEnabled)
            if (i == C.INDEX_UNSET || i == first || i in list) break
            list.add(i)
        }
        return list
    }

    /** Makes the song at [index] the next one to play. With shuffle on, a plain move would put it at a random place, so the shuffle order is set. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun promote(a: ExoPlayer, index: Int) {
        if (!a.shuffleModeEnabled) { a.moveMediaItem(index, a.nextMediaItemIndex); return }
        val timeline = a.currentTimeline
        val order = ArrayList<Int>()
        var i = timeline.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET) { order.add(i); i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true) }
        order.remove(index)
        order.add(order.indexOf(a.currentMediaItemIndex) + 1, index)
        a.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
    }

    /** Listens to the first seconds of the next songs on the idle deck, one at a time, and remembers their tempo, key and energy. */
    private fun scouting(a: ExoPlayer, id: String) {
        // The tempo and key of the songs ahead are asked from the catalogue at once: they give a first score before any song is listened to.
        for (i in upcoming(a)) {
            val item = a.getMediaItemAt(i)
            val key = item.mediaId
            if ((key in profiles && profiles[key]?.summary?.genre != null) || !asked.add(key)) continue
            host.rememberTrack(item)
            scope.launch { host.onlineHint(item)?.let { hint -> online[key] = hint; hint.genre?.let { learnGenre(key, it) } } }
        }
        // The listening starts at once, also right after the listener changed the song: it does not wait for the song that plays to be analysed.
        val scoutSlot = DjMix.SCOUT_SLOT
        val s = scout
        if (s != null) {
            val result = scoutAnalyzer
            val done = result.seconds >= SCOUT_SECONDS || System.currentTimeMillis() - scoutSince > SCOUT_TIMEOUT_MS || s.playerError != null
            if (done) {
                val key = scoutId
                if (key != null) remember(key, Profile(TrackSummary(result.beat, result.key?.camelot, result.energyIntro ?: result.energyRecent, result.origin, swing = result.swing), result.rate))
                // With the rhythm model on, a song ahead is also read whole, so that the model can find its beats and bars.
                val item = scout?.currentMediaItem
                if (key != null && item != null && options.neural && io.github.lnrdo.mytidal.playback.dj.neural.RhythmStore.songs.value[key] == null)
                    DjMix.preprocess.tryEmit(PreprocessRequest(key, host.titleOf(item) ?: key, item, s.duration.coerceAtLeast(0L)))
                endScout()
            }
            return
        }
        val ahead = upcoming(a)
        scouted = ahead.count { a.getMediaItemAt(it).mediaId in profiles }; scoutTotal = ahead.size
        for (i in ahead) {
            val item = a.getMediaItemAt(i)
            if (item.mediaId in profiles || item.mediaId == id) continue
            startScout(item, scoutSlot)
            return
        }
    }

    /**
     * The song was changed by hand: all the songs in the queue are scored again against the song that plays now, and the one that mixes best goes next, if it
     * is clearly better than the one that is there ([REVALIDATE_GAIN] more, or the next one is below the limit). It runs each time that the listening has learned
     * more songs, until all of them are known, or after [REVALIDATE_MS], or when the normal choice is due. A song that the listener put next is not moved.
     */
    private fun revalidate(a: ExoPlayer, remaining: Long) {
        val slot = DjMix.sessionSlot
        val ahead = upcoming(a)
        val known = ahead.count { a.getMediaItemAt(it).mediaId in profiles }
        val everything = scout == null && ahead.isNotEmpty() && known >= ahead.size
        if (everything || System.currentTimeMillis() - revalidateSince > REVALIDATE_MS || remaining <= PREROLL_MS + CHOOSE_MS) revalidating = false
        if (known == revalidatedWith && !everything) return
        revalidatedWith = known
        val current = a.currentMediaItem?.mediaId?.let { profiles[it] }
        val out = analyzers[slot]
        // The song that plays is known from what was heard of it so far, or from what was learned of it before.
        val outgoing = (if (out.beat != null) TrackSummary(out.beat, out.key?.camelot, out.energyRecent, out.origin, swing = out.swing.takeIf { options.groove })
            else current?.summary?.let { if (options.groove) it else it.withoutSwing() } ?: return).let { it.withGenre(genreOf(a.currentMediaItem?.mediaId, it)) }
        val rate = DjMix.streams[slot].ring?.rate ?: current?.rate ?: 44_100
        val next = a.nextMediaItemIndex
        if (next == C.INDEX_UNSET || a.getMediaItemAt(next).mediaId == DjMix.pinnedNext) return
        val arc = if (options.arc) EnergyArc(played.toList() + listOfNotNull(outgoing.energy)) else null
        val scored = ahead.mapNotNull { i ->
            val measured = profiles[a.getMediaItemAt(i).mediaId] ?: return@mapNotNull null
            val summary = if (options.groove) measured.summary else measured.summary.withoutSwing()
            val d = MixPlanner.decide(outgoing, summary, rate, measured.rate)
            Triple(i, d.match, d.match + MixPlanner.rankBonus(d.kind, options.beatFirst) + 0.1 * (arc?.score(summary.energy) ?: 0.5))
        }
        val here = scored.firstOrNull { it.first == next } ?: return
        val best = scored.filter { it.first != next && it.second >= options.minMatch }.maxByOrNull { it.third } ?: return
        if (here.second >= options.minMatch && best.third < here.third + REVALIDATE_GAIN) return
        android.util.Log.i("OpenTune", "D'JAI checked the queue: %s goes next, %.0f%% against %.0f%% for %s".format(host.titleOf(a.getMediaItemAt(best.first)), best.second * 100, here.second * 100, host.titleOf(a.getMediaItemAt(next))))
        promote(a, best.first)
        changedTitle = host.titleOf(a.getMediaItemAt(a.nextMediaItemIndex)); changedMatch = best.second
        choiceMatch[a.getMediaItemAt(a.nextMediaItemIndex).mediaId] = best.second
    }

    private fun startScout(item: MediaItem, scoutSlot: Int) {
        val b = host.buildAnalysis(scoutSlot)
        DjMix.streams[scoutSlot].clear()
        // The mixer never reads this deck. It only decodes, so that the analyzer can listen.
        b.setMediaItem(item)
        scoutAnalyzer.hintBpm = hintFor(item)
        // Nobody hears this deck, so it plays at the fastest speed that the player allows. The tap sits before the speed change, so the analyzer still sees
        // every frame of the song at its own speed: twenty seconds of it are read in a few seconds.
        b.setPlaybackParameters(androidx.media3.common.PlaybackParameters(SCOUT_SPEED))
        b.playWhenReady = true
        b.prepare()
        scoutAnalyzer.stop(); scoutAnalyzer.start()
        scout = b; scoutId = item.mediaId; scoutSince = System.currentTimeMillis()
    }

    private fun endScout() {
        val s = scout ?: return
        scout = null; scoutId = null
        s.stop(); s.release()
        scoutAnalyzer.stop()
        DjMix.streams[DjMix.SCOUT_SLOT].clear()
    }

    /**
     * Reads what was learned about songs in earlier sessions. Only what scoring needs is kept: the tempo, the key, the energy and the bar.
     * A song that is known is not listened to again, so it is ready the moment the queue shows it.
     */
    private fun loadProfiles(into: MutableMap<String, Profile>) {
        runCatching {
            val file = host.profileFile()
            if (!file.exists()) return
            val lines = file.readLines()
            if (lines.firstOrNull() != PROFILE_VERSION) return
            for (line in lines.drop(1)) {
                val f = line.split('\t')
                if (f.size < 8) continue
                val bpm = f[1].toDoubleOrNull()
                val beat = if (bpm == null) null else BeatAnalysis(bpm, f[2].toDouble(), 0.0, 0, f[3].toFloat(), f[4].toInt(), false)
                val genre = f.getOrNull(9)?.let { name -> io.github.lnrdo.mytidal.core.dj.GenreFamily.entries.firstOrNull { it.name == name } }
                into[f[0]] = Profile(TrackSummary(beat, f[5].takeIf { it != "-" }, f[6].toDoubleOrNull(), null, swing = f.getOrNull(8)?.toDoubleOrNull(), genre = genre), f[7].toInt())
            }
        }
    }

    private var saveJob: Job? = null

    /** The ids of the songs that have cue points, and the job that publishes what is known, a moment after the last change. */
    private val cueIds = HashSet<String>()
    private var knownJob: Job? = null

    private fun publishKnown() {
        if (knownJob?.isActive == true) return
        knownJob = scope.launch {
            delay(KNOWN_DELAY_MS)
            val map = HashMap<String, DjMix.SongInfo>(profiles.size + cueIds.size)
            for ((id, p) in profiles) map[id] = DjMix.SongInfo(p.summary.beat?.bpm, p.summary.camelot, p.summary.energy, id in cueIds, p.summary.genre)
            for (id in cueIds) if (id !in map) map[id] = DjMix.SongInfo(null, null, null, true)
            DjMix.known.value = map
        }
    }

    /** Tells the engine which songs have cue points, when the app starts. */
    fun refreshKnown(withCues: Collection<String>) { cueIds.clear(); cueIds.addAll(withCues); publishKnown() }

    /** Forgets all that D'JAI kept about songs: the profiles and the cue points. */
    fun forgetAll() { profiles.clear(); cueIds.clear(); runCatching { host.profileFile().delete() }; publishKnown() }

    /** A song got cue points. */
    fun noteCues(id: String) { cueIds.add(id); publishKnown() }

    /**
     * Writes the profiles to the file. Several songs are often learned within seconds of each other, so the write waits a moment and then writes them all
     * at once, and not one file for each song.
     */
    private fun saveProfiles() {
        if (saveJob?.isActive == true) return
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            writeProfiles()
        }
    }

    private suspend fun writeProfiles() {
        val lines = ArrayList<String>(profiles.size + 1)
        lines.add(PROFILE_VERSION)
        for ((id, p) in profiles) {
            val b = p.summary.beat
            lines.add(listOf(id.replace('\t', '_').replace('\n', '_'), b?.bpm?.toString() ?: "-", b?.periodSamples?.toString() ?: "0", b?.confidence?.toString() ?: "0",
                b?.beatsPerBar?.toString() ?: "4", p.summary.camelot ?: "-", p.summary.energy?.toString() ?: "-", p.rate.toString(), p.summary.swing?.toString() ?: "-", p.summary.genre?.name ?: "-").joinToString("\t"))
        }
        val file = host.profileFile()
        withContext(Dispatchers.IO) { runCatching { synchronized(this@DjEngine) { file.writeText(lines.joinToString("\n")) } } }
    }

    /** Forgets what was learned about songs. */
    fun forgetProfiles() { profiles.clear(); runCatching { host.profileFile().delete() }; publishKnown() }

    /** A profile from the catalogue: the tempo and the key are known, the energy and the grid are not. */
    private fun provisional(hint: OnlineHint): Profile? {
        val bpm = hint.bpm
        if (bpm == null && hint.camelot == null) return null
        val beat = bpm?.let { BeatAnalysis(it, 60.0 / it * PROVISIONAL_RATE, 0.0, 0, 0.6f) }
        return Profile(TrackSummary(beat, hint.camelot, null, null, genre = hint.genre), PROVISIONAL_RATE)
    }

    /** The songs whose catalogue tempo was logged. */
    private val hintLogged = HashSet<String>()

    /** The tempo that a catalogue gives for the song of [item]: from TIDAL's data, or what was asked of the catalogues. The log says whether there is one. */
    private fun hintFor(item: MediaItem?): Double? {
        val bpm = item?.mediaId?.let { io.github.lnrdo.mytidal.playback.dj.neural.RhythmStore.bpmFor(it) } ?: catalogBpm(item) ?: item?.mediaId?.let { online[it]?.bpm }
        item?.mediaId?.let { if (hintLogged.add(it)) android.util.Log.i("OpenTune", "D'JAI catalogue tempo of ${host.titleOf(item)}: ${bpm?.let { b -> "%.1f".format(b) } ?: "none"}") }
        return bpm
    }

    /** The family of style of the song [id], for a summary built from what is being heard: what a catalogue said, what was kept, or a guess. */
    private fun genreOf(id: String?, heard: TrackSummary?): io.github.lnrdo.mytidal.core.dj.GenreFamily? =
        id?.let { online[it]?.genre ?: profiles[it]?.summary?.genre } ?: io.github.lnrdo.mytidal.core.dj.GenreFamily.guess(heard?.beat?.bpm, heard?.swing, heard?.energy)

    /** A catalogue named the style of a song that is known already: it replaces a guess. */
    private fun learnGenre(id: String, genre: io.github.lnrdo.mytidal.core.dj.GenreFamily) {
        val p = profiles[id] ?: return
        if (p.summary.genre == genre) return
        profiles[id] = Profile(p.summary.withGenre(genre), p.rate)
        saveProfiles(); publishKnown()
    }

    /** Keeps what a song that was prepared on request taught: it replaces what the short listening found. */
    fun learn(id: String, summary: TrackSummary, rate: Int) { remember(id, Profile(summary, rate)) }

    private fun remember(id: String, profile: Profile) {
        publishKnown()
        // The family of style: a catalogue's word first, then what was kept before, then a guess from the sound when it is a clear case.
        val s = profile.summary
        val genre = online[id]?.genre ?: s.genre ?: profiles[id]?.summary?.genre ?: io.github.lnrdo.mytidal.core.dj.GenreFamily.guess(s.beat?.bpm, s.swing, s.energy)
        profiles.remove(id)
        profiles[id] = if (genre == s.genre) profile else Profile(s.withGenre(genre), profile.rate)
        while (profiles.size > MAX_PROFILES) profiles.remove(profiles.keys.first())
        saveProfiles()
    }

    /**
     * Makes sure that the next track is a good one. The songs after the current one that were listened to are scored against the end of the
     * current track. When the next one matches by less than 70 percent, the best match takes its place in the queue.
     */
    private fun chooseNext(a: ExoPlayer) {
        val slot = DjMix.sessionSlot
        val out = analyzers[slot]
        val rate = DjMix.streams[slot].ring?.rate ?: return
        val outgoing = TrackSummary(out.beat, out.key?.camelot, out.energyRecent, out.origin, swing = out.swing.takeIf { options.groove })
            .let { it.withGenre(genreOf(a.currentMediaItem?.mediaId, it)) }
        val next = a.nextMediaItemIndex
        if (next == C.INDEX_UNSET) return
        if (a.getMediaItemAt(next).mediaId == DjMix.pinnedNext) return
        // A song that was listened to is scored from what was heard, and one that was not from what the catalogue says.
        val candidates = upcoming(a).mapNotNull { i ->
            val key = a.getMediaItemAt(i).mediaId
val measured = profiles[key]
            (measured ?: online[key]?.let(::provisional))?.let { Candidate(i, if (options.groove) it.summary else it.summary.withoutSwing(), it.rate, verified = measured != null) }
        }
        android.util.Log.i("OpenTune", "D'JAI scores: " + candidates.joinToString { c ->
            "%s%s %.0f%%".format(if (c.index == next) "*" else "", host.titleOf(a.getMediaItemAt(c.index)), MixPlanner.decide(outgoing, c.summary, rate, c.rate).match * 100)
        })
        // The songs that played last shape the choice: a run of loud songs, or of quiet ones, gives way to one that breaks it.
        val arc = if (options.arc) EnergyArc(played.toList() + listOfNotNull(outgoing.energy)) else null
        val best = MixPlanner.choose(outgoing, rate, candidates, next, arc, options.minMatch, options.beatFirst) ?: return
        val match = MixPlanner.decide(outgoing, best.summary, rate, best.rate).match
        if (best.index != next) {
            promote(a, best.index)
            changedTitle = host.titleOf(a.getMediaItemAt(a.nextMediaItemIndex)); changedMatch = match
            android.util.Log.i("OpenTune", "D'JAI changed the next song for a better match: %.0f%%".format(match * 100))
        } else { changedTitle = null; changedMatch = match }
        a.getMediaItemAt(a.nextMediaItemIndex).mediaId.let { choiceMatch[it] = match }
        if (choiceMatch.size > 64) choiceMatch.remove(choiceMatch.keys.first())
    }

    /**
     * Looks again for a better next song, with what was read of the outgoing song and of the next one while they decoded. The songs ahead that were listened to
     * are scored against the outgoing song, and the best one that goes clearly better than [current] takes the place of the next one. Returns whether it did.
     */
    private fun rechoose(a: ExoPlayer, outgoing: TrackSummary, current: Double, rate: Int): Boolean {
        val next = a.nextMediaItemIndex
        if (a.getMediaItemAt(next).mediaId == DjMix.pinnedNext) return false
        val best = upcoming(a).filter { it != next }.mapNotNull { i ->
            profiles[a.getMediaItemAt(i).mediaId]?.let { Triple(i, if (options.groove) it.summary else it.summary.withoutSwing(), it.rate) }
        }.map { (i, summary, r) -> MixPlanner.decide(outgoing, summary, rate, r).let { d -> Triple(i, d.match, d.match + MixPlanner.rankBonus(d.kind, options.beatFirst)) } }
            .filter { it.second >= maxOf(options.minMatch, current + RECHOOSE_GAIN) }.maxByOrNull { it.third }?.let { Pair(it.first, it.second) } ?: return false
        android.util.Log.i("OpenTune", "D'JAI changed the next song: %.0f%% on the mix, %.0f%% for %s".format(current * 100, best.second * 100, host.titleOf(a.getMediaItemAt(best.first))))
        promote(a, best.first)
        changedTitle = host.titleOf(a.getMediaItemAt(a.nextMediaItemIndex)); changedMatch = best.second
        choiceMatch[a.getMediaItemAt(a.nextMediaItemIndex).mediaId] = best.second
        return true
    }

    private fun startPreroll(a: ExoPlayer, next: Int, id: String) {
        outSlot = DjMix.sessionSlot; inSlot = 1 - outSlot
        val items = (0 until a.mediaItemCount).map(a::getMediaItemAt)
        val b = host.buildDeck(inSlot)
        b.shuffleModeEnabled = a.shuffleModeEnabled
        b.repeatMode = a.repeatMode
        b.pauseAtEndOfMediaItems = a.pauseAtEndOfMediaItems
        // A song whose drop is far into it starts a little before the blend that leads to the drop, so that it will have been decoded in time.
        incomingCues = if (options.cues && djai) host.cuesOf(items[next].mediaId) else emptyList()
        incomingStartMs = startForDrop(items[next].mediaId, incomingCues)
        b.setMediaItems(items, next, incomingStartMs)
        analyzers[inSlot].hintBpm = hintFor(items[next])
        DjMix.streams[inSlot].clear()
        DjMix.mixer.setSource(inSlot, DjMix.streams[inSlot])
        DjMix.mixer.setGain(inSlot, host.gainFor(items[next]))
        if (djai) { analyzers[inSlot].stop(); analyzers[inSlot].start(); analyzers[outSlot].start() }
        b.playWhenReady = a.playWhenReady
        b.addListener(mirrorIn)
        b.prepare()
        a.addListener(watcher); a.addListener(mirrorOut)
        outgoing = a; incoming = b
        itemId = id
        nextTitle = host.titleOf(items[next])
        plan = null; decision = null; swapped = false
        state = State.PREPARING
    }

    /** Where to start the incoming song: at the beginning, or before the blend into its drop when the drop is too far to be decoded in time. */
    private fun startForDrop(id: String, cues: List<Cue>): Long {
        val drop = cues.firstOrNull { it.kind == CueKind.DROP } ?: return 0L
        if (drop.atMs <= DROP_FAR_MS) return 0L
        val bpm = profiles[id]?.summary?.beat?.bpm ?: online[id]?.bpm ?: DEFAULT_BPM
        val blendMs = (32 * 60_000.0 / bpm).toLong()
        return (drop.atMs - blendMs - DROP_LEAD_MS).coerceAtLeast(0L)
    }

    // The next track decodes. When its tempo, key and energy are known, the transition is planned.
    private fun preparing() {
        val a = outgoing ?: return abort("lost")
        val b = incoming ?: return abort("lost")
        if (b.playerError != null) return abort("deck error")
        val remaining = a.duration - a.currentPosition
        if (remaining < (if (djai) 16_000L else crossfadeMs + 1_500L)) return abort("too late")
        val analysisIn = analyzers[inSlot]; val analysisOut = analyzers[outSlot]
        val ringOut = DjMix.streams[outSlot].ring ?: return
        val ringIn = DjMix.streams[inSlot].ring ?: return
        if (!djai) {
            // A plain crossfade needs only a few seconds of the next track in its ring.
            if (ringIn.written - ringIn.discontinuity < 2.5 * ringIn.rate) return
            val pos = DjMix.mixer.position(outSlot)
            val end = pos + (remaining / 1000.0 - END_BIAS_SECONDS) * ringOut.rate
            val lengthOut = (crossfadeMs.toLong() * DjMix.OUT_RATE / 1000).toInt()
            val span = lengthOut.toDouble() * ringOut.rate / DjMix.OUT_RATE
            val start = end - 0.3 * ringOut.rate - span
            if (start < pos + 0.3 * ringOut.rate) return abort("too late")
            val simple = MixPlan(outSlot, inSlot, start, ringIn.oldest().toDouble(), lengthOut, 1.0, MixKind.CROSSFADE, 0, 0, 0.5)
            plan = simple; decision = null
            cue(b, ringIn, simple)
            return
        }
        val enough = analysisIn.seconds >= READ_SECONDS
        val short = remaining < SOFT_FALLBACK_MS && analysisIn.seconds >= 5f
        if (!enough && !short) return
        // The cue points of the songs, as frames of their rings. The outgoing song is placed by the position of its player, which is where the ring is being filled.
        val outCues = if (options.cues) host.cuesOf(itemId.orEmpty()).filter { it.atMs > a.currentPosition }
            .map { CueMark(it.kind, ringOut.written + (it.atMs - a.currentPosition) * ringOut.rate / 1000.0) } else emptyList()
        val inCues = incomingCues.filter { it.atMs >= incomingStartMs }
            .map { CueMark(it.kind, ringIn.discontinuity + (it.atMs - incomingStartMs) * ringIn.rate / 1000.0) }
        // What the listening ahead learned about the song, from more of it than is decoded now, goes first: the choice and the plan then see the same song.
        val incomingId = b.currentMediaItem?.mediaId
        val known = incomingId?.let { profiles[it] }?.summary
        val summaryOut = TrackSummary(analysisOut.beat, analysisOut.key?.camelot, analysisOut.energyRecent, analysisOut.origin, outCues, analysisOut.swing.takeIf { options.groove })
            .let { it.withGenre(genreOf(itemId, it)) }
        val summaryIn = TrackSummary(analysisIn.beat, known?.camelot ?: analysisIn.key?.camelot, known?.energy ?: (analysisIn.energyIntro ?: analysisIn.energyRecent), analysisIn.origin, inCues,
            (known?.swing ?: analysisIn.swing).takeIf { options.groove })
            .let { it.withGenre(known?.genre ?: genreOf(incomingId, it)) }
        val pos = DjMix.mixer.position(outSlot)
        // The pointer of the mixer trails the audible position by a few hundred milliseconds, so the end is placed a little early.
        val end = pos + (remaining / 1000.0 - END_BIAS_SECONDS) * ringOut.rate
        val first = ringIn.oldest().toDouble() + ringIn.rate * 0.1
        var chosen = MixPlanner.decide(summaryOut, summaryIn, ringOut.rate, ringIn.rate)
        // A song at twice or half the tempo is mixed on the beat with its grid read at half or double time: the plan and the kick lock use that grid.
        val gridIn = if (chosen.gridFactor != 1.0) summaryIn.withBeat(summaryIn.beat?.scaled(chosen.gridFactor)) else summaryIn
        if (chosen.gridFactor != 1.0 && checkedFor != incomingId) android.util.Log.i("OpenTune", "D'JAI mixes the next song at %s time".format(if (chosen.gridFactor > 1.0) "half" else "double"))
        if (checkedFor != incomingId) android.util.Log.i("OpenTune", "D'JAI styles: %s into %s".format(summaryOut.genre ?: "unknown", summaryIn.genre ?: "unknown"))
        if (checkedFor != incomingId) android.util.Log.i("OpenTune", "D'JAI match for the mix %.0f%% (tempo %.2f, key %.2f, energy %.2f, groove %s), at the choice %s".format(chosen.match * 100, chosen.tempoScore,
            chosen.keyScore, chosen.energyScore, chosen.grooveScore?.let { "%.2f".format(it) } ?: "unknown", incomingId?.let { choiceMatch[it] }?.let { "%.0f%%".format(it * 100) } ?: "unknown"))
        // A mix whose match is low, with what is known now, is given another song when there is time and a better one: it must be a good mix.
        checkedFor = incomingId
        if (chosen.match < options.minMatch && rechosenFor != itemId && remaining > RECHOOSE_MS) {
            // The songs ahead are looked at again once for each song, whether or not a better one is found.
            rechosenFor = itemId
            if (rechoose(a, summaryOut, chosen.match, ringOut.rate)) {
                abort("a better song")
                attempted = null
                return
            }
        }
        var found: MixPlan? = null
        if (chosen.kind == MixKind.BEAT) {
            // Two loud songs get a loop roll before the swap.
            val roll = options.roll && (chosen.energyOutgoing ?: 0.0) >= ROLL_ENERGY && (chosen.energyIncoming ?: 0.0) >= ROLL_ENERGY
            found = MixScheduler.schedule(outSlot, inSlot, chosen, summaryOut, gridIn, ringOut.rate, ringIn.rate, DjMix.OUT_RATE, pos, end, first, roll)
            // A phrase boundary may be a few seconds away, so the planner waits for one unless the track is nearly over.
            if (found == null && !short) return
        }
        if (found == null) {
            // The songs are as alike as they were: a mix that did not fit on the beat is soft, but its match is not lowered.
            chosen = MixDecision(MixKind.SOFT, 1.0, chosen.keyScore, chosen.bpmOutgoing, chosen.bpmIncoming, chosen.energyOutgoing, chosen.energyIncoming, chosen.energyScore, chosen.tempoScore, 16, chosen.grooveScore, chosen.genreScore)
            found = MixScheduler.schedule(outSlot, inSlot, chosen, summaryOut, summaryIn, ringOut.rate, ringIn.rate, DjMix.OUT_RATE, pos, end, first, echo = options.echo) ?: return
        } else {
            found = lockKicks(found, summaryOut.beat!!, gridIn.beat!!, ringOut, ringIn)
        }
        plan = found; decision = chosen
        planEffects = listOfNotNull(if (found.kind == MixKind.BEAT && chosen.gridFactor > 1.0) "HALF" else if (found.kind == MixKind.BEAT && chosen.gridFactor < 1.0) "DOUBLE" else null,
            if (found.roll != null) "ROLL" else null, if (found.dropEntry) "DROP" else null, if (found.echo != null) "ECHO" else null,
            if (outCues.any { it.kind == CueKind.OUTRO }) "OUTRO" else null).joinToString(" ")
        if (planEffects.isNotEmpty()) android.util.Log.i("OpenTune", "D'JAI effects: $planEffects")
        planBpmIn = analysisIn.beat?.bpm; planKeyIn = summaryIn.camelot
        cue(b, ringIn, found)
    }

    /** Sends the incoming player back to a little before where the mix enters it, and holds it there. The plan goes to the mixer once the player is there. */
    private fun cue(b: ExoPlayer, ringIn: io.github.lnrdo.mytidal.core.dj.PcmRing, found: MixPlan) {
        // Where the mix enters the song, in milliseconds of the song: the frames of the ring count from where the player began.
        enterMs = incomingStartMs + (found.startIncoming - ringIn.discontinuity) * 1000.0 / ringIn.rate
        cueMs = (enterMs - CUE_LEAD_MS).toLong().coerceAtLeast(0L)
        cueDiscontinuity = ringIn.discontinuity
        cueSince = System.currentTimeMillis()
        pending = found
        incomingHeld = true
        b.playWhenReady = false
        // The analyzer of the deck starts again after the seek, and reads where in the song it starts from here: the song is learned from its start.
        positions[inSlot] = cueMs
        b.seekTo(cueMs)
        android.util.Log.i("OpenTune", "D'JAI cue: the incoming song enters at %.1f s, held at %.1f s".format(enterMs / 1000, cueMs / 1000.0))
        state = State.CUEING
    }

    /** Waits for the seek of the cue to reach the ring, then gives the mixer the plan with the entry at its new frame. */
    private fun cueing() {
        val a = outgoing ?: return abort("lost")
        val b = incoming ?: return abort("lost")
        val found = pending ?: return abort("lost")
        if (b.playerError != null) return abort("deck error")
        if (a.duration - a.currentPosition < 2_000) return abort("missed")
        val ringIn = DjMix.streams[inSlot].ring ?: return
        // The seek flushes the ring: frames from the new discontinuity on are the song from the cue on.
        if (ringIn.discontinuity == cueDiscontinuity) {
            if (System.currentTimeMillis() - cueSince > CUE_TIMEOUT_MS) abort("cue")
            return
        }
        val placed = found.copy(startIncoming = ringIn.discontinuity + (enterMs - cueMs) * ringIn.rate / 1000.0)
        placedDiscontinuity = ringIn.discontinuity
        plan = placed; pending = null
        finishedBase = DjMix.mixer.finishedSeq
        DjMix.mixer.setPlan(placed)
        state = State.READY
    }

    /**
     * Puts the kicks of the two tracks on top of each other. The grids come from all of the music below 4 kHz, so they can lie a few
     * milliseconds from the bass drum. The kick is measured in the low band of each track against its own grid, and the incoming track is shifted by the
     * difference.
     */
    private fun lockKicks(plan: MixPlan, a: BeatAnalysis, b: BeatAnalysis, ringOut: PcmRing, ringIn: PcmRing): MixPlan {
        val kickOut = KickAligner.offsetSeconds(ringOut, a.anchorSample, a.periodSamples, ringOut.written - 9.0 * a.periodSamples, 8)
        val kickIn = KickAligner.offsetSeconds(ringIn, b.anchorSample, b.periodSamples, plan.startIncoming, 8)
        if (kickOut == null || kickIn == null) return plan
        // The incoming grid beat must fall d seconds after the outgoing one, so that kick meets kick.
        val d = (kickOut - kickIn / plan.speedIncoming).coerceIn(-MAX_KICK_SHIFT, MAX_KICK_SHIFT)
        android.util.Log.i("OpenTune", "D'JAI kick lock: out %.1f ms, in %.1f ms, shift %.1f ms".format(kickOut * 1000, kickIn * 1000, d * 1000))
        return plan.copy(startIncoming = plan.startIncoming - d * plan.speedIncoming * ringIn.rate)
    }

    private fun ready() {
        val a = outgoing ?: return abort("lost")
        if (incoming?.playerError != null) return abort("deck error")
        var p = plan
        val ringOut = DjMix.streams[outSlot].ring
        // A second flush of the incoming deck (the player can flush again on its way) moves the entry with it, as long as the mix has not begun.
        val ringIn = DjMix.streams[inSlot].ring
        if (p != null && ringIn != null && ringIn.discontinuity != placedDiscontinuity && !DjMix.mixer.mixing) {
            p = p.copy(startIncoming = ringIn.discontinuity + (enterMs - cueMs) * ringIn.rate / 1000.0)
            placedDiscontinuity = ringIn.discontinuity
            plan = p
            DjMix.mixer.setPlan(p)
        }
        if (incomingHeld && p != null && ringOut != null) {
            // The player decodes in real time, so it is let go a little more than the lead before the mix: the entry is in the ring when the mix reads it.
            val toStart = (p.startOutgoing - DjMix.mixer.position(outSlot)) / ringOut.rate * 1000.0
            if (toStart <= (enterMs - cueMs) + RELEASE_MARGIN_MS) {
                incomingHeld = false
                incoming?.playWhenReady = a.playWhenReady
            }
        }
        if (DjMix.mixer.mixing) { state = State.MIXING; return }
        if (a.duration - a.currentPosition < 2_000) abort("missed")
    }

    private fun mixing() {
        if (!swapped && DjMix.mixer.progress >= (plan?.swap ?: 0.5)) swap()
        if (DjMix.mixer.finishedSeq != finishedBase) finish()
    }

    /**
     * The incoming deck was given the queue as it was when the song was prepared. If the listener changed the queue since (added, moved or removed songs
     * after the next one), the deck takes the queue of the song that is leaving, so that nothing that was added is lost when the deck becomes the player.
     */
    private fun syncQueue(a: ExoPlayer, b: ExoPlayer) {
        val items = (0 until a.mediaItemCount).map(a::getMediaItemAt)
        if (items.map { it.mediaId } == (0 until b.mediaItemCount).map { b.getMediaItemAt(it).mediaId }) return
        val target = a.nextMediaItemIndex
        val at = b.currentMediaItemIndex
        if (target == C.INDEX_UNSET || items.getOrNull(target)?.mediaId != b.currentMediaItem?.mediaId) return
        runCatching {
            b.replaceMediaItems(at + 1, b.mediaItemCount, items.subList(target + 1, items.size))
            if (at > 0) b.replaceMediaItems(0, at, items.subList(0, target))
        }
    }

    // The bass has changed hands: the second deck now carries the music, so its player becomes the one of the session.
    private fun swap() {
        val a = outgoing ?: return
        val b = incoming ?: return
        a.removeListener(watcher); a.removeListener(mirrorOut)
        // The outgoing song has been heard from its start: its cue points are found now and kept for the next time it plays.
        // The song that just played goes into the history of the set.
        (decision?.energyOutgoing ?: analyzers[outSlot].energyRecent)?.let { played.addLast(it); while (played.size > EnergyArc.MAX) played.removeFirst() }
        val heardId = itemId
        if (heardId != null && djai) {
            val input = analyzers[outSlot].sectionInput(a.duration)
            if (input != null) {
                if (host.cuesOf(heardId).isEmpty()) {
                    val found = detectCues(input)
                    if (found.isNotEmpty()) {
                        host.saveCues(heardId, found)
                        noteCues(heardId)
                        android.util.Log.i("OpenTune", "D'JAI cues of the song: " + found.joinToString { "${it.kind} ${it.atMs / 1000}s" })
                    }
                }
                // The words, when the song has timed lyrics, tell the verses, the choruses and the bridge better than the harmony: they replace those.
                scope.launch {
                    val lyrics = runCatching { host.lyricsOf(heardId) }.getOrNull() ?: return@launch
                    val refined = detectCues(input, lyrics)
                    if (refined.any { it.kind == CueKind.CHORUS }) {
                        host.saveCues(heardId, refined)
                        noteCues(heardId)
                        android.util.Log.i("OpenTune", "D'JAI cues of the song from its words: " + refined.joinToString { "${it.kind} ${it.atMs / 1000}s" })
                    }
                }
            }
        }
        DjMix.sessionSlot = inSlot
        syncQueue(a, b)
        host.adopt(b)
        swapped = true
        b.addListener(tailWatcher)
    }

    private fun finish() {
        incoming?.removeListener(tailWatcher)
        val a = outgoing
        a?.let { it.removeListener(watcher); it.removeListener(mirrorOut); it.stop(); it.release() }
        incoming?.removeListener(mirrorIn)
        DjMix.mixer.setSource(outSlot, null)
        DjMix.streams[outSlot].clear()
        analyzers[outSlot].stop()
        outgoing = null; incoming = null; plan = null; decision = null; swapped = false
        incomingHeld = false; pending = null; planBpmIn = null; planKeyIn = null
        attempted = null
        state = State.IDLE
    }

    /** Cancels a prepared or running transition. The track that is heard keeps playing. */
    private fun abort(reason: String) {
        if (state == State.IDLE || state == State.OFF) return
        android.util.Log.i("OpenTune", "D'JAI aborted: $reason")
        val a = outgoing; val b = incoming
        if (swapped) {
            // The second deck is already the session player. The mixer hears it alone from now on.
            b?.removeListener(tailWatcher)
            DjMix.mixer.cancel(inSlot)
            a?.let { it.removeListener(mirrorOut); it.stop(); it.release() }
            b?.removeListener(mirrorIn)
            DjMix.mixer.setSource(outSlot, null); DjMix.streams[outSlot].clear(); analyzers[outSlot].stop()
        } else {
            DjMix.mixer.cancel(outSlot)
            a?.removeListener(watcher); a?.removeListener(mirrorOut)
            b?.let { it.removeListener(mirrorIn); it.stop(); it.release() }
            DjMix.mixer.setSource(inSlot, null); DjMix.streams[inSlot].clear(); analyzers[inSlot].stop()
            attempted = itemId
        }
        outgoing = null; incoming = null; plan = null; decision = null; swapped = false
        incomingHeld = false; pending = null; planBpmIn = null; planKeyIn = null
        state = if (state == State.OFF) State.OFF else State.IDLE
    }

    private fun publish() {
        val p = plan
        val rate = DjMix.streams[outSlot].ring?.rate ?: 44_100
        val idle = state == State.IDLE
        val out = if (idle) analyzers[DjMix.sessionSlot] else analyzers[outSlot]
        val inn = analyzers[inSlot]
        val slotOut = if (idle) DjMix.sessionSlot else outSlot
        DjMix.gridOut = out.beat?.let { DjMix.LiveGrid(slotOut, it.anchorSample, it.periodSamples, out.rate) }
        DjMix.gridIn = if (idle) null else inn.beat?.let { DjMix.LiveGrid(inSlot, it.anchorSample, it.periodSamples, inn.rate) }
        val phase = when (state) {
            State.OFF -> DjPhase.OFF
            State.IDLE -> DjPhase.WAITING
            State.PREPARING -> DjPhase.READING
            State.CUEING, State.READY -> DjPhase.PLANNED
            State.MIXING -> DjPhase.MIXING
        }
        val d = decision
        val starts = if (p != null && state == State.READY) ((p.startOutgoing - DjMix.mixer.position(outSlot)) / rate * 1000).toLong().coerceAtLeast(0) else 0L
        DjMix.publish(DjUi(
            phase = phase, kind = p?.kind, beats = p?.beats ?: 48, blendBeats = p?.blendBeats ?: 32, swap = p?.swap ?: (2.0 / 3.0),
            bpmOut = out.beat?.bpm, bpmIn = if (idle) null else inn.beat?.bpm ?: planBpmIn,
            keyOut = out.key?.camelot, keyIn = if (idle) null else inn.key?.camelot ?: planKeyIn,
            energyOut = if (idle) out.energyRecent else (d?.energyOutgoing ?: out.energyRecent),
            energyIn = if (idle) null else (d?.energyIncoming ?: inn.energyIntro),
            keyScore = d?.keyScore ?: 0.5, match = d?.match, speed = p?.speedIncoming ?: 1.0, startsInMs = starts,
            lengthMs = p?.let { it.lengthOut * 1000L / DjMix.OUT_RATE } ?: 0L,
            nextTitle = if (idle) null else nextTitle, album = albumNow, effects = if (state == State.READY || state == State.MIXING) planEffects else "",
            changed = changedTitle, changedMatch = changedMatch, scouted = scouted, scoutTotal = scoutTotal,
            genreOut = host.player.currentMediaItem?.mediaId?.let { genreOf(it, null) },
            genreIn = if (idle) host.player.let { pl -> pl.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { pl.getMediaItemAt(it).mediaId } }?.let { genreOf(it, null) }
                else incoming?.currentMediaItem?.mediaId?.let { genreOf(it, null) },
        ))
    }
    private companion object {
        const val TICK_MS = 200L
        const val PREROLL_MS = 75_000L
        /** The cue sits this long before the entry, and the held player is let go this long, and the margin, before the mix. */
        const val CUE_LEAD_MS = 1_500.0
        const val RELEASE_MARGIN_MS = 1_000.0
        /** A seek that has not reached the ring after this long gives up the transition. */
        const val CUE_TIMEOUT_MS = 4_000L
        const val MIN_REMAINING_MS = 34_000L
        const val MIN_TRACK_MS = 90_000L
        const val SOFT_FALLBACK_MS = 38_000L
        const val READ_SECONDS = 18f
        const val END_BIAS_SECONDS = 0.4
        /** A crossfade starts the next track this long before the blend begins. */
        const val CROSSFADE_PREROLL_MS = 9_000L
        /** How many of the next songs are listened to, how long each is listened to, and how long before the last minute the best match is chosen. */
        /** The seconds of each song that are listened to. */
        const val SCOUT_SECONDS = 20f
        /** A song that is not read in this time is skipped. At 8 times the speed it takes a few seconds. */
        const val SCOUT_TIMEOUT_MS = 30_000L
        /** The speed of the player that listens, the fastest that the audio chain allows. */
        const val SCOUT_SPEED = 8f
        const val CHOOSE_MS = 6_000L
        const val MAX_PROFILES = 1500
        /** The profiles are written this long after the first change, and all together. */
        const val SAVE_DELAY_MS = 3_000L
        /** What is known is published this long after the first change, and all together. */
        const val KNOWN_DELAY_MS = 500L
        /** A drop further into the song than this is reached by starting the song later. */
        const val DROP_FAR_MS = 40_000L
        /** How much of the song before the blend into the drop is decoded first, enough for the tempo to be read. */
        const val DROP_LEAD_MS = 16_000L
        /** A plan that cannot enter at the drop yet is put off until the outgoing song has this little time left. */
        const val DROP_WAIT_MS = 35_000L
        /** A mix whose match turns out low is given another song only while the outgoing song has this much time left. */
        const val RECHOOSE_MS = 48_000L
        /** After a change by hand, this many songs of the queue are checked, for at most this long, and a song replaces the next one if it is this much better. */
        const val REVALIDATE_AHEAD = 30
        const val REVALIDATE_MS = 45_000L
        const val REVALIDATE_GAIN = 0.08
        /** The song that replaces a next song with a low match must go at least this much better. */
        const val RECHOOSE_GAIN = 0.05
        const val DEFAULT_BPM = 124.0
        /** Both songs need this much energy, on the scale of 1 to 10, for a roll. */
        const val ROLL_ENERGY = 5.0
        private const val PROVISIONAL_RATE = 44_100
        /** The version of the profile file. Version 2 follows the kick for the tempo, so tempos that were kept at double or half are read again. */
        private const val PROFILE_VERSION = "v2"
        const val MAX_KICK_SHIFT = 0.03
    }
}
