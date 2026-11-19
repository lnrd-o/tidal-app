package io.github.lnrdo.mytidal.core

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrl

object SignInCallback {
    const val NATIVE = "tidal://login/auth"

    fun matches(address: String, expected: String): Boolean {
        if (expected != NATIVE) return false
        return try {
            val actual = URI(address.trim())
            val target = URI(expected)
            actual.scheme == target.scheme && actual.host == target.host && actual.rawPath == target.rawPath &&
                actual.port == target.port && actual.rawUserInfo == null && actual.rawFragment == null
        } catch (_: Exception) { false }
    }

    fun supported(address: String) = matches(address, NATIVE)

    fun parameters(address: String): Map<String, List<String>> {
        val query = URI(address.trim()).rawQuery.orEmpty()
        val parsed = ("https://callback.invalid/?$query").toHttpUrl()
        return parsed.queryParameterNames.associateWith { parsed.queryParameterValues(it).map { value -> value.orEmpty() } }
    }
}
