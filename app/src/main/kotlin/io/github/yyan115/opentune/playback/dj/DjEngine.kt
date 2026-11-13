package io.github.yyan115.opentune.playback.dj

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.yyan115.opentune.core.dj.BeatAnalysis
import io.github.yyan115.opentune.core.dj.Cue
import io.github.yyan115.opentune.core.dj.CueKind
import io.github.yyan115.opentune.core.dj.CueMark
import io.github.yyan115.opentune.core.dj.Candidate
import io.github.yyan115.opentune.core.dj.KickAligner
import io.github.yyan115.opentune.core.dj.PcmRing
import io.github.yyan115.opentune.core.dj.MixDecision
import io.github.yyan115.opentune.core.dj.MixKind
import io.github.yyan115.opentune.core.dj.MixPlan
import io.github.yyan115.opentune.core.dj.MixPlanner
import io.github.yyan115.opentune.core.dj.MixScheduler
import io.github.yyan115.opentune.core.dj.TrackSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The file in which what the listening ahead learned about songs is kept between sessions, in the files folder. */
const val DJAI_PROFILE_FILE = "djai_profiles.tsv"

/** The tempo and the key of a song that an online catalogue gave, so that it can be scored before it is listened to. */
class OnlineHint(val bpm: Double?, val camelot: String?)

/** What the engine needs from the playback service. */
@UnstableApi
interface DjHost {
    val player: ExoPlayer
    /** Builds a player with the same settings as the main one, bound to the deck [slot] and without audio focus. */
    fun buildDeck(slot: Int): ExoPlayer
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
    private enum class State { OFF, IDLE, PREPARING, READY, MIXING }

    private val output = DjOutput()
    private val analyzers = arrayOf(DeckAnalyzer(0, scope) { songPosition(0) }, DeckAnalyzer(1, scope) { songPosition(1) })

    /** The position of the song of a deck in milliseconds: the session player when the deck is the session slot, else the player that is prepared. */
    private fun songPosition(slot: Int): Long = (if (slot == DjMix.sessionSlot) host.player else incoming ?: scout)?.currentPosition ?: -1L

    /** Whether D'JAI uses loop rolls and cue points inside its transitions. */
    @Volatile private var effects = true
    /** Where the incoming song began, in milliseconds, and its cue points, for the transition that is prepared. */
    private var incomingStartMs = 0L
    private var incomingCues: List<Cue> = emptyList()
    /** What the plan of this transition does that is more than a mix, for the strip. */
    private var planEffects = ""
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

    private val watcher = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { if (!swapped) abort("discontinuity $reason") }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { if (!swapped) abort("transition $reason") }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) { if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED && !swapped) abort("queue changed") }
    }
    private val mirrorOut = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { incoming?.let { if (it.playWhenReady != playWhenReady) it.playWhenReady = playWhenReady } }
    }
    private val mirrorIn = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { outgoing?.let { if (it.playWhenReady != playWhenReady) it.playWhenReady = playWhenReady } }
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) { if (!swapped) abort("deck error") }
    }

    /** A new track started on the session player without a flush (a gapless change). Its analysis starts again from just before now. */
    fun onTrackChanged() {
        if (state != State.IDLE) return
        val slot = DjMix.sessionSlot
        DjMix.streams[slot].ring?.let { analyzers[slot].restart(maxOf(it.discontinuity, it.written - (0.3 * it.rate).toLong())) }
    }

    /** Sets what the engine does: D'JAI automix, or only a crossfade of [crossfadeSeconds]. Call it before [setEnabled]. */
    fun configure(djai: Boolean, crossfadeSeconds: Int, elzar: Boolean = false, effects: Boolean = true) {
        val wasDjai = this.djai
        this.effects = effects
        this.elzar = elzar
        this.djai = djai; crossfadeMs = crossfadeSeconds * 1000
        if (state == State.OFF || wasDjai == djai) return
        if (!djai) { endScout(); analyzers.forEach { it.stop() } }
        else if (!suspended) analyzers[DjMix.sessionSlot].start()
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
        DjMix.streams.forEach { it.clear() }
        DjMix.mixer.cancel(slot)
        DjMix.mixer.setSource(slot, DjMix.streams[slot])
        DjMix.mixer.setSource(1 - slot, null)
        DjMix.mixer.setGain(slot, host.gainFor(host.player.currentMediaItem))
        DjMix.routing = true
        if (djai) analyzers[slot].start()
        output.start()
    }

    private fun stopRouting() {
        endScout()
        DjMix.routing = false
        output.stop()
        analyzers.forEach { it.stop() }
        DjMix.mixer.setSource(0, null); DjMix.mixer.setSource(1, null)
        DjMix.streams.forEach { it.clear() }
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
    private fun upcoming(a: ExoPlayer): List<Int> {
        val timeline = a.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val list = ArrayList<Int>()
        var i = a.currentMediaItemIndex
        val first = i
        while (list.size < SCOUT_COUNT) {
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
            if (key in profiles || !asked.add(key)) continue
            scope.launch { host.onlineHint(item)?.let { online[key] = it } }
        }
        val slot = DjMix.sessionSlot
        if (analyzers[slot].beat == null) return
        val scoutSlot = 1 - slot
        val s = scout
        if (s != null) {
            val result = analyzers[scoutSlot]
            val done = result.seconds >= SCOUT_SECONDS || System.currentTimeMillis() - scoutSince > SCOUT_TIMEOUT_MS || s.playerError != null
            if (done) {
                val key = scoutId
                if (key != null) remember(key, Profile(TrackSummary(result.beat, result.key?.camelot, result.energyIntro ?: result.energyRecent, result.origin), result.rate))
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

    private fun startScout(item: MediaItem, scoutSlot: Int) {
        val b = host.buildDeck(scoutSlot)
        DjMix.streams[scoutSlot].clear()
        // The mixer never reads this deck. It only decodes, so that the analyzer can listen.
        DjMix.mixer.setSource(scoutSlot, null)
        b.setMediaItem(item)
        b.playWhenReady = true
        b.prepare()
        analyzers[scoutSlot].stop(); analyzers[scoutSlot].start()
        scout = b; scoutId = item.mediaId; scoutSince = System.currentTimeMillis()
    }

    private fun endScout() {
        val s = scout ?: return
        scout = null; scoutId = null
        s.stop(); s.release()
        val slot = 1 - DjMix.sessionSlot
        analyzers[slot].stop()
        DjMix.streams[slot].clear()
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
                into[f[0]] = Profile(TrackSummary(beat, f[5].takeIf { it != "-" }, f[6].toDoubleOrNull(), null), f[7].toInt())
            }
        }
    }

    private fun saveProfiles() {
        val lines = ArrayList<String>(profiles.size + 1)
        lines.add(PROFILE_VERSION)
        for ((id, p) in profiles) {
            val b = p.summary.beat
            lines.add(listOf(id.replace('\t', '_').replace('\n', '_'), b?.bpm?.toString() ?: "-", b?.periodSamples?.toString() ?: "0", b?.confidence?.toString() ?: "0",
                b?.beatsPerBar?.toString() ?: "4", p.summary.camelot ?: "-", p.summary.energy?.toString() ?: "-", p.rate.toString()).joinToString("\t"))
        }
        val file = host.profileFile()
        scope.launch(Dispatchers.IO) { runCatching { synchronized(this@DjEngine) { file.writeText(lines.joinToString("\n")) } } }
    }

    /** Forgets what was learned about songs. */
    fun forgetProfiles() { profiles.clear(); runCatching { host.profileFile().delete() } }

    /** A profile from the catalogue: the tempo and the key are known, the energy and the grid are not. */
    private fun provisional(hint: OnlineHint): Profile? {
        val bpm = hint.bpm
        if (bpm == null && hint.camelot == null) return null
        val beat = bpm?.let { BeatAnalysis(it, 60.0 / it * PROVISIONAL_RATE, 0.0, 0, 0.6f) }
        return Profile(TrackSummary(beat, hint.camelot, null, null), PROVISIONAL_RATE)
    }

    private fun remember(id: String, profile: Profile) {
        profiles.remove(id)
        profiles[id] = profile
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
        val outgoing = TrackSummary(out.beat, out.key?.camelot, out.energyRecent, out.origin)
        val next = a.nextMediaItemIndex
        if (next == C.INDEX_UNSET) return
        // A song that was listened to is scored from what was heard, and one that was not from what the catalogue says.
        val candidates = upcoming(a).mapNotNull { i ->
            val key = a.getMediaItemAt(i).mediaId
            (profiles[key] ?: online[key]?.let(::provisional))?.let { Candidate(i, it.summary, it.rate) }
        }
        android.util.Log.i("OpenTune", "D'JAI scores: " + candidates.joinToString { c ->
            "%s%s %.0f%%".format(if (c.index == next) "*" else "", host.titleOf(a.getMediaItemAt(c.index)), MixPlanner.decide(outgoing, c.summary, rate, c.rate).match * 100)
        })
        val best = MixPlanner.choose(outgoing, rate, candidates, next) ?: return
        val match = MixPlanner.decide(outgoing, best.summary, rate, best.rate).match
        if (best.index != next) {
            promote(a, best.index)
            changedTitle = host.titleOf(a.getMediaItemAt(a.nextMediaItemIndex)); changedMatch = match
            android.util.Log.i("OpenTune", "D'JAI changed the next song for a better match: %.0f%%".format(match * 100))
        } else { changedTitle = null; changedMatch = match }
    }

    private fun startPreroll(a: ExoPlayer, next: Int, id: String) {
        outSlot = DjMix.sessionSlot; inSlot = 1 - outSlot
        val items = (0 until a.mediaItemCount).map(a::getMediaItemAt)
        val b = host.buildDeck(inSlot)
        b.shuffleModeEnabled = a.shuffleModeEnabled
        b.repeatMode = a.repeatMode
        b.pauseAtEndOfMediaItems = a.pauseAtEndOfMediaItems
        // A song whose drop is far into it starts a little before the blend that leads to the drop, so that it will have been decoded in time.
        incomingCues = if (effects && djai) host.cuesOf(items[next].mediaId) else emptyList()
        incomingStartMs = startForDrop(items[next].mediaId, incomingCues)
        b.setMediaItems(items, next, incomingStartMs)
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
            finishedBase = DjMix.mixer.finishedSeq
            DjMix.mixer.setPlan(simple)
            state = State.READY
            return
        }
        val enough = analysisIn.seconds >= READ_SECONDS
        val short = remaining < SOFT_FALLBACK_MS && analysisIn.seconds >= 5f
        if (!enough && !short) return
        // The cue points of the songs, as frames of their rings. The outgoing song is placed by the position of its player, which is where the ring is being filled.
        val outCues = if (effects) host.cuesOf(itemId.orEmpty()).filter { it.atMs > a.currentPosition }
            .map { CueMark(it.kind, ringOut.written + (it.atMs - a.currentPosition) * ringOut.rate / 1000.0) } else emptyList()
        val inCues = incomingCues.filter { it.atMs >= incomingStartMs }
            .map { CueMark(it.kind, ringIn.discontinuity + (it.atMs - incomingStartMs) * ringIn.rate / 1000.0) }
        val summaryOut = TrackSummary(analysisOut.beat, analysisOut.key?.camelot, analysisOut.energyRecent, analysisOut.origin, outCues)
        val summaryIn = TrackSummary(analysisIn.beat, analysisIn.key?.camelot, analysisIn.energyIntro ?: analysisIn.energyRecent, analysisIn.origin, inCues)
        val pos = DjMix.mixer.position(outSlot)
        // The pointer of the mixer trails the audible position by a few hundred milliseconds, so the end is placed a little early.
        val end = pos + (remaining / 1000.0 - END_BIAS_SECONDS) * ringOut.rate
        val first = ringIn.oldest().toDouble() + ringIn.rate * 0.1
        var chosen = MixPlanner.decide(summaryOut, summaryIn, ringOut.rate, ringIn.rate)
        var found: MixPlan? = null
        if (chosen.kind == MixKind.BEAT) {
            // Two loud songs get a loop roll before the swap.
            val roll = effects && (chosen.energyOutgoing ?: 0.0) >= ROLL_ENERGY && (chosen.energyIncoming ?: 0.0) >= ROLL_ENERGY
            found = MixScheduler.schedule(outSlot, inSlot, chosen, summaryOut, summaryIn, ringOut.rate, ringIn.rate, DjMix.OUT_RATE, pos, end, first, roll, ringIn.written.toDouble())
            // A song with a known drop waits until enough of it is decoded to enter at the drop, unless the outgoing song is nearly over.
            if (found != null && effects && !found.dropEntry && incomingCues.any { it.kind == CueKind.DROP } && remaining > DROP_WAIT_MS) return
            // A phrase boundary may be a few seconds away, so the planner waits for one unless the track is nearly over.
            if (found == null && !short) return
        }
        if (found == null) {
            chosen = MixDecision(MixKind.SOFT, 1.0, chosen.keyScore, chosen.bpmOutgoing, chosen.bpmIncoming, chosen.energyOutgoing, chosen.energyIncoming, chosen.energyScore, 0.2, 16)
            found = MixScheduler.schedule(outSlot, inSlot, chosen, summaryOut, summaryIn, ringOut.rate, ringIn.rate, DjMix.OUT_RATE, pos, end, first) ?: return
        } else {
            found = lockKicks(found, summaryOut.beat!!, summaryIn.beat!!, ringOut, ringIn)
        }
        plan = found; decision = chosen
        planEffects = listOfNotNull(if (found.roll != null) "ROLL" else null, if (found.dropEntry) "DROP" else null,
            if (outCues.any { it.kind == CueKind.OUTRO }) "OUTRO" else null).joinToString(" ")
        if (planEffects.isNotEmpty()) android.util.Log.i("OpenTune", "D'JAI effects: $planEffects")
        finishedBase = DjMix.mixer.finishedSeq
        DjMix.mixer.setPlan(found)
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
        if (DjMix.mixer.mixing) { state = State.MIXING; return }
        if (a.duration - a.currentPosition < 2_000) abort("missed")
    }

    private fun mixing() {
        if (!swapped && DjMix.mixer.progress >= (plan?.swap ?: 0.5)) swap()
        if (DjMix.mixer.finishedSeq != finishedBase) finish()
    }

    // The bass has changed hands: the second deck now carries the music, so its player becomes the one of the session.
    private fun swap() {
        val a = outgoing ?: return
        val b = incoming ?: return
        a.removeListener(watcher); a.removeListener(mirrorOut)
        // The outgoing song has been heard from its start: its cue points are found now and kept for the next time it plays.
        val heardId = itemId
        if (heardId != null && djai) {
            val found = analyzers[outSlot].cues(a.duration)
            if (found.isNotEmpty()) {
                host.saveCues(heardId, found)
                android.util.Log.i("OpenTune", "D'JAI cues of the song: " + found.joinToString { "${it.kind} ${it.atMs / 1000}s" })
            }
        }
        DjMix.sessionSlot = inSlot
        host.adopt(b)
        swapped = true
    }

    private fun finish() {
        val a = outgoing
        a?.let { it.removeListener(watcher); it.removeListener(mirrorOut); it.stop(); it.release() }
        incoming?.removeListener(mirrorIn)
        DjMix.mixer.setSource(outSlot, null)
        DjMix.streams[outSlot].clear()
        analyzers[outSlot].stop()
        outgoing = null; incoming = null; plan = null; decision = null; swapped = false
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
        state = if (state == State.OFF) State.OFF else State.IDLE
    }

    private fun publish() {
        val p = plan
        val rate = DjMix.streams[outSlot].ring?.rate ?: 44_100
        val idle = state == State.IDLE
        val out = if (idle) analyzers[DjMix.sessionSlot] else analyzers[outSlot]
        val inn = analyzers[inSlot]
        val phase = when (state) {
            State.OFF -> DjPhase.OFF
            State.IDLE -> DjPhase.WAITING
            State.PREPARING -> DjPhase.READING
            State.READY -> DjPhase.PLANNED
            State.MIXING -> DjPhase.MIXING
        }
        val d = decision
        val starts = if (p != null && state == State.READY) ((p.startOutgoing - DjMix.mixer.position(outSlot)) / rate * 1000).toLong().coerceAtLeast(0) else 0L
        DjMix.publish(DjUi(
            phase = phase, kind = p?.kind, beats = p?.beats ?: 48, blendBeats = p?.blendBeats ?: 32, swap = p?.swap ?: (2.0 / 3.0),
            bpmOut = out.beat?.bpm, bpmIn = if (idle) null else inn.beat?.bpm,
            keyOut = out.key?.camelot, keyIn = if (idle) null else inn.key?.camelot,
            energyOut = if (idle) out.energyRecent else (d?.energyOutgoing ?: out.energyRecent),
            energyIn = if (idle) null else (d?.energyIncoming ?: inn.energyIntro),
            keyScore = d?.keyScore ?: 0.5, match = d?.match, speed = p?.speedIncoming ?: 1.0, startsInMs = starts,
            lengthMs = p?.let { it.lengthOut * 1000L / DjMix.OUT_RATE } ?: 0L,
            nextTitle = if (idle) null else nextTitle, album = albumNow, effects = if (state == State.READY || state == State.MIXING) planEffects else "",
            changed = changedTitle, changedMatch = changedMatch, scouted = scouted, scoutTotal = scoutTotal,
        ))
    }
    private companion object {
        const val TICK_MS = 200L
        const val PREROLL_MS = 62_000L
        const val MIN_REMAINING_MS = 34_000L
        const val MIN_TRACK_MS = 90_000L
        const val SOFT_FALLBACK_MS = 38_000L
        const val READ_SECONDS = 14f
        const val END_BIAS_SECONDS = 0.4
        /** A crossfade starts the next track this long before the blend begins. */
        const val CROSSFADE_PREROLL_MS = 9_000L
        /** How many of the next songs are listened to, how long each is listened to, and how long before the last minute the best match is chosen. */
        const val SCOUT_COUNT = 6
        const val SCOUT_SECONDS = 15f
        const val SCOUT_TIMEOUT_MS = 45_000L
        const val CHOOSE_MS = 6_000L
        const val MAX_PROFILES = 1500
        /** A drop further into the song than this is reached by starting the song later. */
        const val DROP_FAR_MS = 40_000L
        /** How much of the song before the blend into the drop is decoded first, enough for the tempo to be read. */
        const val DROP_LEAD_MS = 16_000L
        /** A plan that cannot enter at the drop yet is put off until the outgoing song has this little time left. */
        const val DROP_WAIT_MS = 35_000L
        const val DEFAULT_BPM = 124.0
        /** Both songs need this much energy, on the scale of 1 to 10, for a roll. */
        const val ROLL_ENERGY = 5.0
        private const val PROVISIONAL_RATE = 44_100
        private const val PROFILE_VERSION = "v1"
        const val MAX_KICK_SHIFT = 0.03
    }
}
