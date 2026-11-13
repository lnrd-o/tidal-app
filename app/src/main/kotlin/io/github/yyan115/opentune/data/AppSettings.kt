package io.github.yyan115.opentune.data

import android.content.Context
import io.github.yyan115.opentune.core.AudioQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val quality: AudioQuality = AudioQuality.LOSSLESS,
    val mobileQuality: AudioQuality = AudioQuality.LOSSLESS,
    val downloadOnWifi: Boolean = true,
    val offline: Boolean = false,
    /** Volume normalization from ReplayGain data: "off", "track" or "album". */
    val normalization: String = "off",
    val bitPerfect: Boolean = false,
    /** D'JAI: mixes the queue as one continuous set. The players only decode and a mixer plays the result. */
    val djai: Boolean = false,
    /** Blends the end of a song into the next one for this many seconds. Zero is off. Albums stay gapless. */
    val crossfadeSeconds: Int = 0,
    /** Tone Lab: studio glue, tape and vinyl character, each from 0 to 100. It plays through the mixer, so bit-perfect output is off. */
    val elzar: Boolean = false,
    val elzarGlue: Int = 40,
    val elzarTape: Int = 30,
    val elzarVinyl: Int = 0,
    /** The index of the glue flavor, in the order of the Flavor enum. */
    /** The size of the cache of listened songs, in megabytes. At 0 nothing is kept. */
    val cacheMb: Int = 256,
    /** Colours the player and the spectrum with the main colour of the cover. Off keeps them white. */
    val adaptColor: Boolean = false,
    /** D'JAI rolls a loop before the swap and uses the cue points of the songs. */
    /** The effects of D'JAI, each on its own. */
    val djRoll: Boolean = true,
    val djCues: Boolean = true,
    val djEcho: Boolean = true,
    val djFilters: Boolean = true,
    /** How D'JAI chooses the next song: it matches grooves, it shapes the set, and it changes a next song that matches by less than [djMinMatch] percent. */
    val djGroove: Boolean = true,
    val djArc: Boolean = true,
    val djMinMatch: Int = 70,
    /** How many of the songs ahead are listened to. */
    val djListenAhead: Int = 12,
    /** Shows the tempo, key and energy that D'JAI kept for a song in the lists. A mark shows in any case. */
    val showDjData: Boolean = true,
    /** The tempo that is shown blinks at its beats per minute. */
    val djPulse: Boolean = true,
    /** Where D'JAI may learn the tempo and key of the songs ahead without listening: 0 nowhere, 1 from TIDAL's own data, 2 also from Deezer by ISRC. */
    val onlineData: Int = 1,
    val elzarFlavor: Int = 0,
    val elzarTapeType: Int = 0,
    val elzarVinylCondition: Int = 1,
    /** True for a record at 45 rpm. */
    val elzarVinylFast: Boolean = false,
    val elzarConsole: Int = 0,
    val elzarTube: Int = 0,
    val elzarAir: Int = 0,
    val elzarClip: Int = 0,
    val elzarMidSide: Boolean = false,
    /** Plays the original at the loudness of the processed sound. */
    val elzarBypass: Boolean = false,
    /** Downloads a track when it is saved with the heart button. At most one of [downloadSaved] and [downloadPlayed] is on. */
    val downloadSaved: Boolean = false,
    /** Downloads each track that counts as played. At most one of [downloadSaved] and [downloadPlayed] is on. */
    val downloadPlayed: Boolean = false,
    val scrobble: Boolean = false,
    val scrobbleServer: String = "https://api.listenbrainz.org",
    /** Whether the first-launch screen was answered. */
    val welcomeDone: Boolean = false,
    /** Keeps playing similar music from TIDAL when the queue ends. */
    val autoplay: Boolean = true,
    /** Tracks played within this many hours come last in shuffle and are left out of radio and autoplay. Zero turns it off. */
    val avoidRepeatHours: Int = 0,
    /** Looks for a newer release when the app opens. */
    val checkUpdates: Boolean = true,
    /** The permissions page has to be shown, because the first screen was answered and the page was not. */
    val setupPending: Boolean = false,
) {
    /** Turns the download of saved tracks on or off. Turning it on turns the download of played tracks off. */
    fun withDownloadSaved(on: Boolean) = copy(downloadSaved = on, downloadPlayed = downloadPlayed && !on)

    /** Turns the download of played tracks on or off. Turning it on turns the download of saved tracks off. */
    fun withDownloadPlayed(on: Boolean) = copy(downloadPlayed = on, downloadSaved = downloadSaved && !on)
}

class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun quality(key: String, fallback: AudioQuality) = runCatching {
        AudioQuality.valueOf(preferences.getString(key, fallback.name)!!)
    }.getOrDefault(fallback)
    private val mutable = MutableStateFlow(Settings(
        quality = quality("quality", AudioQuality.LOSSLESS), mobileQuality = quality("mobileQuality", AudioQuality.LOSSLESS),
        downloadOnWifi = preferences.getBoolean("downloadOnWifi", true), offline = preferences.getBoolean("offline", false),
        normalization = preferences.getString("normalization", "off") ?: "off",        bitPerfect = preferences.getBoolean("bitPerfect", false), djai = preferences.getBoolean("djai", false), crossfadeSeconds = preferences.getInt("crossfadeSeconds", 0).coerceIn(0, 12), elzar = preferences.getBoolean("elzar", false),
        elzarGlue = preferences.getInt("elzarGlue", 40).coerceIn(0, 100), elzarTape = preferences.getInt("elzarTape", 30).coerceIn(0, 100), elzarVinyl = preferences.getInt("elzarVinyl", 0).coerceIn(0, 100), elzarFlavor = preferences.getInt("elzarFlavor", 0).coerceIn(0, 6), cacheMb = preferences.getInt("cacheMb", 256).coerceIn(0, 8192), adaptColor = preferences.getBoolean("adaptColor", false), djRoll = preferences.getBoolean("djRoll", preferences.getBoolean("djEffects", true)),
        djCues = preferences.getBoolean("djCues", preferences.getBoolean("djEffects", true)), djEcho = preferences.getBoolean("djEcho", preferences.getBoolean("djEffects", true)),
        djFilters = preferences.getBoolean("djFilters", true), djGroove = preferences.getBoolean("djGroove", true), djArc = preferences.getBoolean("djArc", true),
        djMinMatch = preferences.getInt("djMinMatch", 70).coerceIn(50, 90), djListenAhead = preferences.getInt("djListenAhead", 12).coerceIn(4, 16), showDjData = preferences.getBoolean("showDjData", true), djPulse = preferences.getBoolean("djPulse", true), onlineData = preferences.getInt("onlineData", 1).coerceIn(0, 2),
        elzarTapeType = preferences.getInt("elzarTapeType", 0).coerceIn(0, 3), elzarVinylCondition = preferences.getInt("elzarVinylCondition", 1).coerceIn(0, 2),
        elzarVinylFast = preferences.getBoolean("elzarVinylFast", false), elzarConsole = preferences.getInt("elzarConsole", 0).coerceIn(0, 100),
        elzarTube = preferences.getInt("elzarTube", 0).coerceIn(0, 100), elzarAir = preferences.getInt("elzarAir", 0).coerceIn(0, 100),
        elzarClip = preferences.getInt("elzarClip", 0).coerceIn(0, 100), elzarMidSide = preferences.getBoolean("elzarMidSide", false),
        elzarBypass = preferences.getBoolean("elzarBypass", false),
        // The option that kept saved tracks offline became the download of saved tracks.
        downloadSaved = preferences.getBoolean("downloadSaved", preferences.getBoolean("keepFavoritesOffline", false)),
        downloadPlayed = preferences.getBoolean("downloadPlayed", false),
        scrobble = preferences.getBoolean("scrobble", false), scrobbleServer = preferences.getString("scrobbleServer", "https://api.listenbrainz.org") ?: "https://api.listenbrainz.org",
        welcomeDone = preferences.getBoolean("welcomeDone", false), autoplay = preferences.getBoolean("autoplay", true),
        avoidRepeatHours = preferences.getInt("avoidRepeatHours", 0),
        checkUpdates = preferences.getBoolean("checkUpdates", true), setupPending = preferences.getBoolean("setupPending", false)).exclusive())
    val state = mutable.asStateFlow()
    fun update(transform: (Settings) -> Settings) {
        val next = transform(mutable.value).exclusive()
        preferences.edit().putString("quality", next.quality.name).putString("mobileQuality", next.mobileQuality.name)
            .putBoolean("downloadOnWifi", next.downloadOnWifi).putBoolean("offline", next.offline)
            .remove("theme").remove("colorTheme").remove("equalizer").remove("equalizerLevels").remove("equalizerCustom").remove("equalizerUsesCustom").remove("visualizer")
            .putString("normalization", next.normalization).putBoolean("bitPerfect", next.bitPerfect).putBoolean("djai", next.djai).putInt("crossfadeSeconds", next.crossfadeSeconds)
            .putBoolean("elzar", next.elzar).putInt("elzarGlue", next.elzarGlue).putInt("elzarTape", next.elzarTape).putInt("elzarVinyl", next.elzarVinyl).putInt("elzarFlavor", next.elzarFlavor).putInt("cacheMb", next.cacheMb).putBoolean("adaptColor", next.adaptColor).putBoolean("djRoll", next.djRoll).putBoolean("djCues", next.djCues).putBoolean("djEcho", next.djEcho).putBoolean("djFilters", next.djFilters)
            .putBoolean("djGroove", next.djGroove).putBoolean("djArc", next.djArc).putInt("djMinMatch", next.djMinMatch).putInt("djListenAhead", next.djListenAhead).putBoolean("showDjData", next.showDjData).putBoolean("djPulse", next.djPulse).putInt("onlineData", next.onlineData)
            .putInt("elzarTapeType", next.elzarTapeType).putInt("elzarVinylCondition", next.elzarVinylCondition).putBoolean("elzarVinylFast", next.elzarVinylFast)
            .putInt("elzarConsole", next.elzarConsole).putInt("elzarTube", next.elzarTube).putInt("elzarAir", next.elzarAir).putInt("elzarClip", next.elzarClip)
            .putBoolean("elzarMidSide", next.elzarMidSide).putBoolean("elzarBypass", next.elzarBypass)
            .putBoolean("downloadSaved", next.downloadSaved).putBoolean("downloadPlayed", next.downloadPlayed).remove("keepFavoritesOffline").putBoolean("scrobble", next.scrobble)
            .putString("scrobbleServer", next.scrobbleServer).putBoolean("welcomeDone", next.welcomeDone).putBoolean("autoplay", next.autoplay)
            .putInt("avoidRepeatHours", next.avoidRepeatHours).putBoolean("checkUpdates", next.checkUpdates).putBoolean("setupPending", next.setupPending).apply()
        mutable.value = next
    }
}

/** Settings that arrive from storage or a backup may have both automatic downloads on. The download of saved tracks stays. */
private fun Settings.exclusive() = if (downloadSaved && downloadPlayed) copy(downloadPlayed = false) else this
