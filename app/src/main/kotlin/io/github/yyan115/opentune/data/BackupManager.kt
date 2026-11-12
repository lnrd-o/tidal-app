package io.github.yyan115.opentune.data

import androidx.media3.common.util.UnstableApi
import io.github.yyan115.opentune.OpenTuneApp
import io.github.yyan115.opentune.core.AudioQuality
import io.github.yyan115.opentune.core.JsonCodec
import io.github.yyan115.opentune.core.Problem
import org.json.JSONArray
import org.json.JSONObject

/** A backup file that cannot be restored. [problem] tells the app which sentence to show. */
class BackupException(val problem: Problem, message: String) : IllegalArgumentException(message)

/**
 * Saves and restores what lives only on the device: settings, device playlists and listening history.
 * TIDAL data stays in the account, and account tokens, downloads and imported files are never included.
 */
@UnstableApi
class BackupManager(private val app: OpenTuneApp) {
    data class Summary(val playlists: Int, val plays: Int)

    fun toJson(): JSONObject {
        val settings = app.settings.state.value
        return JSONObject().apply {
            put("format", "opentune-backup"); put("version", 1); put("createdAt", System.currentTimeMillis())
            put("settings", JSONObject().apply {
                put("quality", settings.quality.name); put("mobileQuality", settings.mobileQuality.name); put("downloadOnWifi", settings.downloadOnWifi)
                put("normalization", settings.normalization); put("bitPerfect", settings.bitPerfect)
                put("downloadSaved", settings.downloadSaved); put("downloadPlayed", settings.downloadPlayed); put("scrobble", settings.scrobble); put("scrobbleServer", settings.scrobbleServer)
            })
            put("playlists", JSONArray().apply {
                app.library.playlists().forEach { playlist ->
                    // Imported files cannot be restored on another install, so only TIDAL tracks are kept.
                    val tracks = app.library.playlistTracks(playlist.id).filterNot { it.isFile }
                    put(JSONObject().put("title", playlist.title).put("tracks", JSONArray(tracks.map(JsonCodec::encodeTrack))))
                }
            })
            put("plays", JSONArray().apply {
                app.library.exportPlays().filterNot { it.track.isFile }.forEach {
                    put(JSONObject().put("track", JsonCodec.encodeTrack(it.track)).put("playedAt", it.playedAt).put("listenedMs", it.listenedMs))
                }
            })
        }
    }

    /** Merges a backup into this install. Existing playlists with the same name receive only the tracks they lack. */
    fun restore(json: JSONObject): Summary {
        if (json.optString("format") != "opentune-backup") throw BackupException(Problem.BACKUP_INVALID, "This file is not an OpenTune backup.")
        if (json.optInt("version") !in 1..1) throw BackupException(Problem.BACKUP_TOO_NEW, "This backup comes from a newer version of OpenTune.")
        json.optJSONObject("settings")?.let { values ->
            app.settings.update { current ->
                fun quality(name: String, fallback: AudioQuality) = runCatching { AudioQuality.valueOf(values.getString(name)) }.getOrDefault(fallback)
                current.copy(
                    quality = quality("quality", current.quality), mobileQuality = quality("mobileQuality", current.mobileQuality),
                    downloadOnWifi = values.optBoolean("downloadOnWifi", current.downloadOnWifi),
                    normalization = values.optString("normalization", current.normalization),
                    bitPerfect = values.optBoolean("bitPerfect", current.bitPerfect), downloadSaved = values.optBoolean("downloadSaved", values.optBoolean("keepFavoritesOffline", current.downloadSaved)),
                    downloadPlayed = values.optBoolean("downloadPlayed", current.downloadPlayed),
                    scrobble = values.optBoolean("scrobble", current.scrobble), scrobbleServer = values.optString("scrobbleServer", current.scrobbleServer),
                )
            }
        }
        var playlists = 0
        json.optJSONArray("playlists")?.let { array ->
            for (index in 0 until array.length()) {
                val entry = array.getJSONObject(index)
                val title = entry.getString("title")
                val existing = app.library.playlists().firstOrNull { it.title == title }
                val playlist = existing ?: app.library.createPlaylist(title)
                val present = existing?.trackIds.orEmpty().toSet()
                val tracks = entry.optJSONArray("tracks")
                if (tracks != null) for (position in 0 until tracks.length()) {
                    val track = JsonCodec.decodeTrack(tracks.getJSONObject(position))
                    if (track.id !in present) app.library.addToPlaylist(playlist.id, track)
                }
                playlists++
            }
        }
        val plays = json.optJSONArray("plays")?.let { array ->
            app.library.importPlays((0 until array.length()).map { position ->
                array.getJSONObject(position).let { LibraryStore.PlayEntry(JsonCodec.decodeTrack(it.getJSONObject("track")), it.getLong("playedAt"), it.getLong("listenedMs")) }
            })
        } ?: 0
        return Summary(playlists, plays)
    }
}
