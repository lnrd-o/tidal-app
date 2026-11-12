package io.github.yyan115.opentune.core

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val albumId: String = "",
    val artwork: String? = null,
    val durationMs: Long = 0,
    val explicit: Boolean = false,
    val localUri: String? = null,
    val quality: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    val artistId: String = "",
    val trackNumber: Int = 0,
    val replayGain: Double? = null,
    val peak: Double? = null,
    val tags: List<String> = emptyList(),
    /** The tempo in beats per minute, and the key as a Camelot code, when the catalogue knows them. */
    val bpm: Double? = null,
    val key: String? = null,
    val isrc: String? = null,
) {
    /** A file that the listener added from the device. It has no TIDAL page, lyrics or radio. */
    val isFile get() = isDeviceId(id)
    /** Whether the audio is stored on the device, as a download or as a file. */
    val onDevice get() = localUri != null
    /** A track that was downloaded from TIDAL. */
    val isDownload get() = onDevice && !isFile
}

/** Files and playlists made on the device have ids that start with this prefix. TIDAL ids are numbers or UUIDs. */
const val DEVICE_ID_PREFIX = "local:"
fun isDeviceId(id: String) = id.startsWith(DEVICE_ID_PREFIX)

data class Release(
    val id: String,
    val title: String,
    val artist: String,
    val artwork: String? = null,
    val subtitle: String = "",
    val type: ReleaseType = ReleaseType.ALBUM,
    val ownerId: String = "",
    val tags: List<String> = emptyList(),
    val description: String = "",
    val artistId: String = "",
)

enum class ReleaseType { ALBUM, PLAYLIST, ARTIST, MIX, VIDEO, FOLDER }

/** A playlist made on the device. It can hold TIDAL tracks and files. */
val Release.isDevicePlaylist get() = isDeviceId(id)
data class SearchResult(
    val tracks: List<Track> = emptyList(),
    val albums: List<Release> = emptyList(),
    val artists: List<Release> = emptyList(),
    val playlists: List<Release> = emptyList(),
    val videos: List<Release> = emptyList(),
    val topRelease: Release? = null,
    val topTrack: Track? = null,
)

/** The levels of audio quality. The app shows a translated name for each one. */
enum class AudioQuality { HI_RES_LOSSLESS, LOSSLESS, HIGH, LOW }

data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String = "",
    val country: String = "US",
) {
    fun needsRefresh(now: Long = System.currentTimeMillis()) = expiresAt <= now + 60_000
    override fun toString() = "Session(authenticated=${accessToken.isNotBlank()}, country=$country)"
}

data class PkceLogin(val authorizationUrl: String, val verifier: String, val state: String,
    val uniqueKey: String, val expiresAt: Long) {
    override fun toString() = "PkceLogin(expiresAt=$expiresAt)"
}

data class StreamInfo(
    val uri: String? = null,
    val dashManifest: String? = null,
    val mimeType: String,
    val codec: String,
    val quality: String,
    val bitDepth: Int = 0,
    val sampleRate: Int = 0,
    val trackGain: Double? = null,
    val trackPeak: Double? = null,
    val albumGain: Double? = null,
    val albumPeak: Double? = null,
) {
    val qualityLabel: String
        get() = buildString {
            append(if (codec.contains("flac", true)) "FLAC" else codec.uppercase())
            if (bitDepth > 0) append(" · $bitDepth-bit")
            if (sampleRate > 0) append(" · ${sampleRate / 1000.0} kHz")
        }
    override fun toString() = "StreamInfo(codec=$codec, quality=$quality, bitDepth=$bitDepth, sampleRate=$sampleRate)"
}

data class LocalPlaylist(val id: String, val title: String, val trackIds: List<String> = emptyList(), val artwork: String? = null)

/**
 * Stable identifiers for the problems that the user can be told about. The app turns each one into a translated sentence,
 * so the English [ServiceException.message] is for logs and tests.
 */
enum class Problem {
    SIGN_IN_EXPIRED, SIGN_IN_WRONG_CALLBACK, SIGN_IN_OTHER_ATTEMPT, SIGN_IN_DECLINED, SIGN_IN_NO_CODE, SIGN_IN_USED, SIGN_IN_CONFIG_REJECTED, SIGN_IN_FAILED,
    SIGN_IN_REQUIRED, SESSION_EXPIRED, SESSION_NOT_RENEWED,
    LOSSY_AUDIO, PREVIEW_ONLY, STREAM_INVALID, STREAM_UNSUPPORTED, PROTECTED_STREAM,
    REFRESH_PLAYLIST, PLAYLIST_CHANGED, UNREADABLE_RESPONSE,
    HTTP_FORBIDDEN, HTTP_NOT_FOUND, HTTP_RATE_LIMIT, HTTP_CONFLICT, HTTP_SERVER, HTTP_OTHER,
    SCROBBLE_TOKEN_MISSING, SCROBBLE_TOKEN_REJECTED, SCROBBLE_LISTEN_REJECTED, SCROBBLE_CHECK_FAILED, SCROBBLE_ADDRESS_INVALID,
    NOT_AVAILABLE_OFFLINE, TRACK_MISSING, TRACK_UNAVAILABLE, BACKUP_INVALID, BACKUP_TOO_NEW;

    companion object {
        fun forStatus(status: Int) = when (status) {
            401 -> SESSION_EXPIRED
            403 -> HTTP_FORBIDDEN
            404 -> HTTP_NOT_FOUND
            429 -> HTTP_RATE_LIMIT
            409, 412 -> HTTP_CONFLICT
            in 500..599 -> HTTP_SERVER
            else -> HTTP_OTHER
        }
    }
}

/**
 * Text that stands in for data that the service left out. The English defaults serve tests and logs,
 * and the app replaces them with translated text when it starts and when the device language changes.
 */
object Placeholders {
    @Volatile var untitled = "Untitled"
    @Volatile var unknownArtist = "Unknown artist"
}

class ServiceException(val status: Int, message: String, val problem: Problem = Problem.forStatus(status)) : Exception(message)
class ProtectedStreamException : Exception("This stream requires content protection that this client does not support.")

fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
