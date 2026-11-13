package io.github.yyan115.opentune.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import com.google.common.util.concurrent.ListenableFuture
import io.github.yyan115.opentune.MainActivity
import io.github.yyan115.opentune.OpenTuneApp
import io.github.yyan115.opentune.playback.dj.DeckTapProcessor
import io.github.yyan115.opentune.playback.dj.DjEngine
import io.github.yyan115.opentune.playback.dj.DjHost
import io.github.yyan115.opentune.playback.dj.DjMix
import io.github.yyan115.opentune.data.DownloadRequests
import io.github.yyan115.opentune.core.JsonCodec
import io.github.yyan115.opentune.core.Normalization
import io.github.yyan115.opentune.core.RepeatPolicy
import io.github.yyan115.opentune.core.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Custom commands that the app sends to the playback service. */
object PlaybackCommands {
    val SleepTimer = SessionCommand("opentune.sleep", Bundle.EMPTY)
    /** Shuffles the tracks after the current one, as when shuffle is turned on. Sent after a new queue was set while shuffle is on. */
    val Reshuffle = SessionCommand("opentune.reshuffle", Bundle.EMPTY)
    const val ARG_MINUTES = "minutes"
    const val ARG_END_OF_TRACK = "endOfTrack"
    const val EXTRA_ENDS_AT = "sleepEndsAt"
    const val EXTRA_AT_TRACK_END = "sleepAtTrackEnd"
}

@UnstableApi
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaLibrarySession? = null
    private val tree by lazy { BrowseTree(app) }
    private lateinit var app: OpenTuneApp
    private lateinit var player: ExoPlayer
    private var sleepJob: Job? = null
    private var sleepAtTrackEnd = false
    private var bitPerfectDevice: AudioDeviceInfo? = null
    /** The queue as it was before shuffle turned on, so that turning it off puts the tracks back in order. */
    private var orderBeforeShuffle: List<MediaItem> = emptyList()
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = applyBitPerfect()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = applyBitPerfect()
    }

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var retryJob: Job? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val error = player.playerError ?: return
            if (isTransient(error)) { android.util.Log.i("OpenTune", "Network is back, preparing the player again"); player.prepare() }
        }
    }

    // Listening time of the current track, for the history and for scrobbling.
    private var listenedTrack: Track? = null
    private var listenStartedAt = 0L
    private var listenedMs = 0L
    private var playingSince = 0L

    private val audioAttributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()

    /**
     * Builds a player. The renderers pass the audio through the tap of the deck [slot] and the processor that lets the analyzer see it.
     * Without D'JAI the audio itself does not change.
     */
    private fun buildPlayer(slot: Int, focus: Boolean): ExoPlayer {
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink {
                AudioSpectrum.sinks++
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).setAudioProcessors(arrayOf(DeckTapProcessor(slot), SpectrumProcessor(slot))).build()
            }
        }
        val built = ExoPlayer.Builder(this, renderers)
            .setLoadControl(streamingLoadControl())
            .setMediaSourceFactory(OpenTuneSourceFactory(app))
            .setAudioAttributes(audioAttributes, focus)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build()
        // Shuffle reorders the queue itself, so that the queue shows what plays next and "play next" and reordering do what they say.
        // The player's own shuffle order stays the natural order.
        built.setShuffleOrder(ShuffleOrder.UnshuffledShuffleOrder(0))
        return built
    }

    /** Makes [next] the player of the session, as when D'JAI has handed the music over to the second deck. */
    private fun adopt(next: ExoPlayer) {
        val old = player
        old.removeListener(playerListener)
        old.setAudioAttributes(audioAttributes, false)
        next.setAudioAttributes(audioAttributes, true)
        next.addListener(playerListener)
        player = next
        session?.player = next
        onItemStarted(next.currentMediaItem)
        applyNormalization()
    }

    private val djHost = object : DjHost {
        override val player: ExoPlayer get() = this@PlaybackService.player
        override fun buildDeck(slot: Int): ExoPlayer = buildPlayer(slot, false)
        override fun adopt(next: ExoPlayer) = this@PlaybackService.adopt(next)
        override fun gainFor(item: MediaItem?): Float = this@PlaybackService.gainFor(item)
        override fun titleOf(item: MediaItem?): String? = item?.let(::trackOf)?.title
        override val sleepAtTrackEnd: Boolean get() = this@PlaybackService.sleepAtTrackEnd
        override fun albumQueue(): Boolean = this@PlaybackService.isAlbumQueue()
        override suspend fun onlineHint(item: MediaItem): io.github.yyan115.opentune.playback.dj.OnlineHint? {
            val mode = app.settings.state.value.onlineData
            if (mode == 0) return null
            val track = item.mediaMetadata.extras?.getString("track")
                ?.let { runCatching { io.github.yyan115.opentune.core.JsonCodec.decodeTrack(org.json.JSONObject(it)) }.getOrNull() } ?: return null
            var bpm = track.bpm
            if (bpm == null && mode >= 2 && !track.isFile) track.isrc?.let { bpm = fetchDeezerBpm(it) }
            return if (bpm == null && track.key == null) null else io.github.yyan115.opentune.playback.dj.OnlineHint(bpm, track.key)
        }

        /** The tempo that Deezer lists for a recording, found by its ISRC. Only the ISRC is sent. Returns null when there is none. */
        private suspend fun fetchDeezerBpm(isrc: String): Double? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val request = okhttp3.Request.Builder().url("https://api.deezer.com/track/isrc:" + android.net.Uri.encode(isrc)).build()
                app.http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    org.json.JSONObject(response.body?.string().orEmpty()).optDouble("bpm", 0.0).takeIf { it in 40.0..250.0 }
                }
            }.getOrNull()
        }

        override fun cuesOf(id: String): List<io.github.yyan115.opentune.core.dj.Cue> =
            runCatching { app.library.cues(id).mapNotNull { (kind, at) -> runCatching { io.github.yyan115.opentune.core.dj.CueKind.valueOf(kind) }.getOrNull()?.let { io.github.yyan115.opentune.core.dj.Cue(it, at) } } }.getOrDefault(emptyList())

        override fun saveCues(id: String, cues: List<io.github.yyan115.opentune.core.dj.Cue>) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.library.saveCues(id, cues.map { it.kind.name to it.atMs }) } }
        }

        override fun profileFile(): java.io.File = java.io.File(filesDir, io.github.yyan115.opentune.playback.dj.DJAI_PROFILE_FILE)
    }
    private var albumKey: String? = null
    private var albumResult = false

    /** True when the queue holds two or more tracks and all of them are from one album. The answer is kept until the queue changes. */
    private fun isAlbumQueue(): Boolean {
        val p = player
        val count = p.mediaItemCount
        if (count < 2) return false
        val key = "$count:${p.getMediaItemAt(0).mediaId}:${p.getMediaItemAt(count / 2).mediaId}:${p.getMediaItemAt(count - 1).mediaId}"
        if (key != albumKey) {
            albumKey = key
            val first = trackOf(p.getMediaItemAt(0))?.albumId
            albumResult = !first.isNullOrBlank() && (0 until count).all { trackOf(p.getMediaItemAt(it))?.albumId == first }
        }
        return albumResult
    }
    private val engine by lazy { DjEngine(djHost, scope) }

    /** What the player does when a new track starts: the history, the listening time and the widget. */
    private fun onItemStarted(mediaItem: MediaItem?) {
        finishListening()
        val track = mediaItem?.let(::trackOf)
        if (track != null) {
            scope.launch(Dispatchers.IO) { runCatching { app.library.played(track) } }
            startListening(track)
        }
        applyNormalization()
        applyBitPerfect()
        updateWidget(reloadArtwork = true)
    }

    private val playerListener = object : Player.Listener {
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { if (shuffleModeEnabled) shuffleUpcoming() else restoreOrder() }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { onItemStarted(mediaItem); engine.onTrackChanged() }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) playingSince = SystemClock.elapsedRealtime()
            else if (playingSince != 0L) { listenedMs += SystemClock.elapsedRealtime() - playingSince; playingSince = 0 }
            updateWidget()
        }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) finishListening()
            if (playbackState == Player.STATE_READY) retryJob?.cancel()
        }
        override fun onPlayerError(error: PlaybackException) {
            val transient = isTransient(error)
            android.util.Log.w("OpenTune", "Playback error ${error.errorCodeName}, transient=$transient, chain=${generateSequence<Throwable>(error) { it.cause }.joinToString(" > ") { it.javaClass.simpleName }}")
            if (transient) scheduleRetry()
        }
        override fun onAudioSessionIdChanged(audioSessionId: Int) { applyBitPerfect() }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepAtTrackEnd) clearSleepTimer()
        }
    }

    override fun attachBaseContext(base: android.content.Context) { super.attachBaseContext(io.github.yyan115.opentune.LocaleFix.wrap(base)) }

    override fun onCreate() {
        super.onCreate()
        app = application as OpenTuneApp
        player = buildPlayer(0, true)
        player.addListener(playerListener)
        app.sources.onGains = { id -> Handler(Looper.getMainLooper()).post { if (player.currentMediaItem?.mediaId == id) applyNormalization() } }
        scope.launch { app.settings.state.collect { app.sources.setCacheLimit(it.cacheMb); applyNormalization(); applyBitPerfect(); DjMix.mixer.setElZar(it.toElZarConfig())
            engine.configure(it.djai, it.crossfadeSeconds, it.elzar, it.djEffects); engine.setEnabled(it.djai || it.crossfadeSeconds > 0 || it.elzar) } }
        scope.launch { runCatching { app.scrobbler.flush() } }
        if (Build.VERSION.SDK_INT >= 23) audioManager.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback, Handler(Looper.getMainLooper())) }

        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaLibrarySession.Builder(this, player, object : MediaLibrarySession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(PlaybackCommands.SleepTimer).add(PlaybackCommands.Reshuffle).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
            }

            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    PlaybackCommands.SleepTimer.customAction -> setSleepTimer(args.getInt(PlaybackCommands.ARG_MINUTES, 0), args.getBoolean(PlaybackCommands.ARG_END_OF_TRACK, false))
                    PlaybackCommands.Reshuffle.customAction -> shuffleUpcoming()
                    else -> return Futures.immediateFuture(SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED))
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
                Futures.immediateFuture(LibraryResult.ofItem(tree.root(), params))

            override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
                Futures.immediateFuture(tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(androidx.media3.session.SessionError.ERROR_BAD_VALUE))

            override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?):
                ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                val result = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                scope.launch {
                    val items = runCatching { withContext(Dispatchers.IO) { tree.children(parentId) } }.getOrDefault(emptyList())
                    val from = (page * pageSize).coerceAtMost(items.size)
                    result.set(LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, (from + pageSize).coerceAtMost(items.size))), params))
                }
                return result
            }

            override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
                scope.launch { session.notifySearchResultChanged(browser, query, runCatching { withContext(Dispatchers.IO) { tree.search(query) } }.getOrDefault(emptyList()).size, params) }
                return Futures.immediateFuture(LibraryResult.ofVoid())
            }

            override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?):
                ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
                val result = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                scope.launch {
                    val items = runCatching { withContext(Dispatchers.IO) { tree.search(query) } }.getOrDefault(emptyList())
                    val from = (page * pageSize).coerceAtMost(items.size)
                    result.set(LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, (from + pageSize).coerceAtMost(items.size))), params))
                }
                return result
            }

            override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
                val result = SettableFuture.create<MutableList<MediaItem>>()
                scope.launch {
                    val expanded = withContext(Dispatchers.IO) {
                        mediaItems.flatMap { item ->
                            val query = item.requestMetadata.searchQuery
                            // A spoken request such as "play Nujabes" arrives as a search query.
                            if (query != null && item.mediaId.isBlank() || query != null && item.localConfiguration == null && tree.item(item.mediaId) == null) runCatching { tree.search(query) }.getOrDefault(emptyList())
                            else listOf(tree.resolve(item))
                        }
                    }
                    result.set(expanded.toMutableList())
                }
                return result
            }
        }).setSessionActivity(activity).build()
    }

    /** Puts the tracks after the current one in random order. The tracks before it and the current one stay where they are. */
    private fun shuffleUpcoming() {
        val count = player.mediaItemCount
        orderBeforeShuffle = (0 until count).map(player::getMediaItemAt)
        val current = player.currentMediaItemIndex
        if (count - current < 3) return
        val hours = app.settings.state.value.avoidRepeatHours
        val recent = if (hours > 0) app.library.playedSince(System.currentTimeMillis() - hours * 3_600_000L) else emptySet()
        player.replaceMediaItems(current + 1, count, RepeatPolicy.deferRecent(orderBeforeShuffle.subList(current + 1, count).shuffled(), { it.mediaId }, recent))
    }

    /**
     * Puts the queue back in the order it had before shuffle turned on. The current track keeps playing.
     * A track that was added after shuffle turned on follows the tracks that were there before.
     */
    private fun restoreOrder() {
        val original = orderBeforeShuffle
        orderBeforeShuffle = emptyList()
        val count = player.mediaItemCount
        if (original.isEmpty() || count == 0) return
        val positions = HashMap<String, ArrayDeque<Int>>()
        original.forEachIndexed { position, item -> positions.getOrPut(item.mediaId) { ArrayDeque() }.addLast(position) }
        val queue = (0 until count).map(player::getMediaItemAt)
        val ranked = queue.mapIndexed { index, item -> Triple(index, item, positions[item.mediaId]?.removeFirstOrNull() ?: (original.size + index)) }.sortedBy { it.third }
        val current = player.currentMediaItemIndex
        val at = ranked.indexOfFirst { it.first == current }
        if (at < 0) return
        // The part after the current track goes first, so that the index of the current track holds while it is replaced.
        player.replaceMediaItems(current + 1, count, ranked.drop(at + 1).map { it.second })
        if (current > 0) player.replaceMediaItems(0, current, ranked.take(at).map { it.second })
    }

    /** Writes what the home screen widget shows. A new cover is fetched in the background. */
    private fun updateWidget(reloadArtwork: Boolean = false) {
        val track = player.currentMediaItem?.let(::trackOf)
        WidgetState.write(this, track?.let { WidgetState.Snapshot(it.title, it.artist, player.isPlaying) })
        NowPlayingWidget.refresh(this)
        if (!reloadArtwork) return
        val artwork = track?.artwork
        scope.launch(Dispatchers.IO) {
            val bytes = artwork?.takeIf { it.startsWith("https://") || it.startsWith("file:") }?.let { address ->
                runCatching {
                    if (address.startsWith("file:")) java.io.File(android.net.Uri.parse(address).path.orEmpty()).readBytes()
                    else app.http.newCall(okhttp3.Request.Builder().url(address).build()).execute().use { response -> if (response.isSuccessful) response.body.bytes() else null }
                }.getOrNull()
            }
            runCatching { WidgetState.saveArtwork(this@PlaybackService, bytes) }
            withContext(Dispatchers.Main) { NowPlayingWidget.refresh(this@PlaybackService) }
        }
    }

    private fun trackOf(item: MediaItem): Track? = item.mediaMetadata.extras?.getString("track")
        ?.let { runCatching { JsonCodec.decodeTrack(JSONObject(it)) }.getOrNull() }

    /** Turns loud tracks down by their ReplayGain value when the listener asked for it. */
    /** The gain that volume normalization gives a track, or 1 when it is off. */
    private fun gainFor(item: MediaItem?): Float {
        val mode = app.settings.state.value.normalization
        val track = item?.let(::trackOf)
        if (mode == Normalization.OFF || track == null) return 1f
        val gains = app.sources.gains(track.id)
        return Normalization.volume(mode, gains?.track ?: track.replayGain, gains?.album)
    }

    private fun applyNormalization() {
        val volume = gainFor(player.currentMediaItem)
        player.volume = volume
        // While D'JAI plays, the mixer applies the gain, because the players only decode.
        DjMix.mixer.setGain(DjMix.sessionSlot, volume)
        SignalPath.normalizationDb = if (volume in 0.0001f..0.9999f) (20 * kotlin.math.log10(volume)) else 0f
    }

    /** Asks Android to send the stream to a USB DAC unchanged. This is experimental and needs Android 14 or later. */
    private fun applyBitPerfect() {
        if (Build.VERSION.SDK_INT < 34 || DjMix.routing) return
        runCatching {
            val attributes = player.audioAttributes.audioAttributesV21.audioAttributes
            val usb = if (app.settings.state.value.bitPerfect) audioManager.getAudioDevicesForAttributes(attributes).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } else null
            bitPerfectDevice?.let { previous -> if (previous.id != usb?.id) { audioManager.clearPreferredMixerAttributes(attributes, previous); bitPerfectDevice = null; SignalPath.bitPerfectDevice = null } }
            if (usb == null) return
            val supported = audioManager.getSupportedMixerAttributes(usb).filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            val rate = player.audioFormat?.sampleRate
            val choice = supported.firstOrNull { it.format.sampleRate == rate } ?: supported.maxByOrNull { it.format.sampleRate } ?: return
            audioManager.setPreferredMixerAttributes(attributes, usb, choice)
            bitPerfectDevice = usb
            SignalPath.bitPerfectDevice = usb.productName?.toString()?.ifBlank { null } ?: "USB"
        }
    }

    /** Turns the music down over [SLEEP_FADE_MS] and pauses it, so that the sleep timer does not cut the sound off. */
    private suspend fun fadeOutAndPause() {
        val base = gainFor(player.currentMediaItem)
        val steps = 60
        for (i in 1..steps) {
            val gain = base * (1f - i / steps.toFloat())
            player.volume = gain
            DjMix.mixer.setGain(DjMix.sessionSlot, gain)
            delay(SLEEP_FADE_MS / steps)
        }
        player.pause()
        applyNormalization()
    }

    private fun setSleepTimer(minutes: Int, endOfTrack: Boolean) {
        clearSleepTimer()
        val extras = Bundle()
        when {
            minutes > 0 -> {
                val endsAt = System.currentTimeMillis() + minutes * 60_000L
                extras.putLong(PlaybackCommands.EXTRA_ENDS_AT, endsAt)
                sleepJob = scope.launch { delay((minutes * 60_000L - SLEEP_FADE_MS).coerceAtLeast(0)); fadeOutAndPause(); clearSleepTimer() }
            }
            endOfTrack -> {
                sleepAtTrackEnd = true; player.pauseAtEndOfMediaItems = true
                extras.putBoolean(PlaybackCommands.EXTRA_AT_TRACK_END, true)
            }
        }
        session?.setSessionExtras(extras)
    }

    private fun clearSleepTimer() {
        sleepJob?.cancel(); sleepJob = null
        if (sleepAtTrackEnd) { sleepAtTrackEnd = false; player.pauseAtEndOfMediaItems = false }
        session?.setSessionExtras(Bundle.EMPTY)
    }

    private fun startListening(track: Track) {
        listenedTrack = track; listenStartedAt = System.currentTimeMillis(); listenedMs = 0
        playingSince = if (player.isPlaying) SystemClock.elapsedRealtime() else 0
        scope.launch { app.scrobbler.playingNow(track) }
    }

    /** Stores the finished listen once it is long enough to count: 30 seconds, and half the track or four minutes. */
    private fun finishListening() {
        val track = listenedTrack ?: return
        if (playingSince != 0L) { listenedMs += SystemClock.elapsedRealtime() - playingSince; playingSince = 0 }
        val listened = listenedMs; val startedAt = listenStartedAt
        listenedTrack = null; listenedMs = 0
        val needed = if (track.durationMs > 0) minOf(track.durationMs / 2, 240_000L).coerceAtLeast(30_000L) else 30_000L
        if (listened < needed) return
        scope.launch(Dispatchers.IO) {
            runCatching { app.library.recordPlay(track, listened, startedAt) }
            runCatching { DownloadRequests.afterPlay(app, track) }
        }
        scope.launch { runCatching { app.scrobbler.listened(track, startedAt) } }
    }

    private companion object { const val SLEEP_FADE_MS = 12_000L }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        val current = session?.player
        if (current == null || !current.playWhenReady || current.mediaItemCount == 0) stopSelf()
    }
    override fun onDestroy() {
        finishListening()
        if (Build.VERSION.SDK_INT >= 23) audioManager.unregisterAudioDeviceCallback(deviceCallback)
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        app.sources.onGains = null
        WidgetState.write(this, null); NowPlayingWidget.refresh(this)
        runCatching { engine.setEnabled(false) }
        scope.cancel(); session?.player?.release(); session?.release(); session = null; super.onDestroy()
    }

    /**
     * Lossless tracks run at several megabits per second, and the default limit of 12.5 MB holds only 11 to 20 seconds of them.
     * The player keeps up to 90 seconds or 64 MB ahead and starts a track after one second of audio.
     */
    private fun streamingLoadControl() = DefaultLoadControl.Builder()
        .setBufferDurationsMs(40_000, 90_000, 1_000, 3_000)
        .setTargetBufferBytes(64 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

    /**
     * A lost connection or an overloaded server passes by, and an expired stream address is replaced when the player prepares again.
     * Other failures, such as a track that no longer exists, do not pass.
     */
    private fun isTransient(error: PlaybackException): Boolean {
        val chain = generateSequence<Throwable>(error) { it.cause }.toList()
        chain.filterIsInstance<io.github.yyan115.opentune.core.ServiceException>().firstOrNull()?.let { return it.status == 429 || it.status in 500..599 }
        chain.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let {
            return it.responseCode == 403 || it.responseCode == 429 || it.responseCode in 500..599
        }
        return error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT || chain.any { it is java.io.IOException }
    }

    /** Prepares the player again after a short wait, and again with longer waits, until playback resumes. */
    private fun scheduleRetry() {
        retryJob?.cancel()
        retryJob = scope.launch {
            for (wait in listOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 30_000L, 30_000L, 30_000L)) {
                delay(wait)
                if (player.playerError == null || !player.playWhenReady) { android.util.Log.i("OpenTune", "Retry stopped: error=${player.playerError != null} playWhenReady=${player.playWhenReady}"); return@launch }
                android.util.Log.i("OpenTune", "Retrying playback after ${wait / 1000} s")
                player.prepare()
            }
        }
    }
}

/** The settings of the tone module, in the form that the mixer takes. */
private fun io.github.yyan115.opentune.data.Settings.toElZarConfig() = io.github.yyan115.opentune.core.dj.ElZarConfig(
    on = elzar, glue = elzarGlue / 100f, tape = elzarTape / 100f, vinyl = elzarVinyl / 100f,
    flavor = io.github.yyan115.opentune.core.dj.Flavor.entries.getOrElse(elzarFlavor) { io.github.yyan115.opentune.core.dj.Flavor.STUDIO },
    tapeType = io.github.yyan115.opentune.core.dj.TapeType.entries.getOrElse(elzarTapeType) { io.github.yyan115.opentune.core.dj.TapeType.STUDIO },
    vinylCondition = io.github.yyan115.opentune.core.dj.VinylCondition.entries.getOrElse(elzarVinylCondition) { io.github.yyan115.opentune.core.dj.VinylCondition.WORN },
    vinylFast = elzarVinylFast, console = elzarConsole / 100f, tube = elzarTube / 100f, air = elzarAir / 100f, clip = elzarClip / 100f,
    midSide = elzarMidSide, bypass = elzarBypass,
)
