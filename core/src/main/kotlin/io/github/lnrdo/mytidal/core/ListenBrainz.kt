package io.github.lnrdo.mytidal.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** A finished listen, ready to be submitted. [listenedAt] is in seconds since the epoch. */
data class Listen(val listenedAt: Long, val artist: String, val title: String, val album: String, val durationMs: Long, val trackNumber: Int = 0)

/**
 * Submits listening history to a ListenBrainz server. The server address is configurable so that
 * a self-hosted server can be used. Nothing is sent unless the listener turns scrobbling on.
 */
class ListenBrainzClient(private val http: OkHttpClient, private val server: () -> String, private val token: () -> String?, private val version: String = "") {
    private val json = "application/json".toMediaType()

    private fun metadata(artist: String, title: String, album: String, durationMs: Long, trackNumber: Int) = JSONObject().apply {
        put("artist_name", artist); put("track_name", title)
        if (album.isNotBlank()) put("release_name", album)
        put("additional_info", JSONObject().apply {
            put("media_player", "OpenTune"); put("submission_client", "OpenTune")
            if (version.isNotBlank()) { put("media_player_version", version); put("submission_client_version", version) }
            if (durationMs > 0) put("duration_ms", durationMs)
            if (trackNumber > 0) put("tracknumber", trackNumber)
        })
    }

    fun listenJson(listen: Listen): JSONObject = JSONObject().put("listened_at", listen.listenedAt)
        .put("track_metadata", metadata(listen.artist, listen.title, listen.album, listen.durationMs, listen.trackNumber))

    /** Shows the current track on the listener's profile. A failure is not worth a retry. */
    fun playingNow(artist: String, title: String, album: String, durationMs: Long) {
        post(JSONObject().put("listen_type", "playing_now").put("payload", JSONArray().put(JSONObject().put("track_metadata", metadata(artist, title, album, durationMs, 0)))))
    }

    /** Submits several listens at once. Throws [ServiceException] for a rejection and [IOException] when the server cannot be reached. */
    fun submit(listens: List<JSONObject>) {
        if (listens.isEmpty()) return
        post(JSONObject().put("listen_type", if (listens.size == 1) "single" else "import").put("payload", JSONArray(listens)))
    }

    /** The user name that the token belongs to, or null when the server rejects the token. */
    fun validate(): String? {
        val request = Request.Builder().url(url("1/validate-token")).header("Authorization", "Token ${requireToken()}").build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ServiceException(response.code, "The server could not check the token (HTTP ${response.code}).", Problem.SCROBBLE_CHECK_FAILED)
            val body = JSONObject(response.body.string())
            if (body.optBoolean("valid")) body.optString("user_name") else null
        }
    }

    private fun post(body: JSONObject) {
        val request = Request.Builder().url(url("1/submit-listens")).header("Authorization", "Token ${requireToken()}")
            .post(body.toString().toRequestBody(json)).build()
        http.newCall(request).execute().use { response ->
            when {
                response.isSuccessful -> Unit
                response.code == 401 -> throw ServiceException(401, "The server rejected the token.", Problem.SCROBBLE_TOKEN_REJECTED)
                response.code in 400..499 -> throw ServiceException(response.code, "The server rejected the listen (HTTP ${response.code}).", Problem.SCROBBLE_LISTEN_REJECTED)
                else -> throw IOException("The server answered with HTTP ${response.code}.")
            }
        }
    }

    private fun requireToken() = token()?.takeIf(String::isNotBlank) ?: throw ServiceException(401, "Add a ListenBrainz token first.", Problem.SCROBBLE_TOKEN_MISSING)

    private fun url(path: String) = (server().trim().trimEnd('/') + "/" + path).let {
        try { it.toHttpUrl() } catch (_: IllegalArgumentException) { throw ServiceException(400, "The server address is not valid.", Problem.SCROBBLE_ADDRESS_INVALID) }
    }
}
