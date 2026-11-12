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
        normalization = preferences.getString("normalization", "off") ?: "off",        bitPerfect = preferences.getBoolean("bitPerfect", false),
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
            .putString("normalization", next.normalization).putBoolean("bitPerfect", next.bitPerfect)
            .putBoolean("downloadSaved", next.downloadSaved).putBoolean("downloadPlayed", next.downloadPlayed).remove("keepFavoritesOffline").putBoolean("scrobble", next.scrobble)
            .putString("scrobbleServer", next.scrobbleServer).putBoolean("welcomeDone", next.welcomeDone).putBoolean("autoplay", next.autoplay)
            .putInt("avoidRepeatHours", next.avoidRepeatHours).putBoolean("checkUpdates", next.checkUpdates).putBoolean("setupPending", next.setupPending).apply()
        mutable.value = next
    }
}

/** Settings that arrive from storage or a backup may have both automatic downloads on. The download of saved tracks stays. */
private fun Settings.exclusive() = if (downloadSaved && downloadPlayed) copy(downloadPlayed = false) else this
