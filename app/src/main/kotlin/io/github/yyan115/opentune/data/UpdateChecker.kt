package io.github.yyan115.opentune.data

import io.github.yyan115.opentune.core.ReleaseNotice
import io.github.yyan115.opentune.core.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Asks GitHub for the releases of the app and reports the newest one when it is later than the installed version. */
class UpdateChecker(private val http: OkHttpClient, private val address: String = "https://api.github.com/repos/lnrd-o/tidal-app/releases?per_page=10") {
    /** The newer release, or null when the app is up to date or the answer could not be read. */
    suspend fun newer(current: String): ReleaseNotice? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(address).header("Accept", "application/vnd.github+json").header("User-Agent", "OpenTune").build()
            http.newBuilder().callTimeout(10, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                UpdateCheck.newest(response.body.string())?.takeIf { UpdateCheck.isNewer(current, it.version) }
            }
        }.getOrNull()
    }
}
