package io.github.lnrdo.mytidal.core

import org.json.JSONArray

/** A published release of the app: its version without the leading "v" and the address of its page. */
data class ReleaseNotice(val version: String, val url: String)

/** Finds out whether a newer release exists, from the list of releases that GitHub returns. */
object UpdateCheck {
    /** The newest release in [json] that is not a draft, or null when the list holds none. */
    fun newest(json: String): ReleaseNotice? {
        val releases = runCatching { JSONArray(json) }.getOrNull() ?: return null
        return (0 until releases.length()).map(releases::getJSONObject)
            .filter { !it.optBoolean("draft") && it.optString("tag_name").isNotBlank() && it.optString("html_url").isNotBlank() }
            .map { ReleaseNotice(it.getString("tag_name").removePrefix("v"), it.getString("html_url")) }
            .filter { parts(it.version) != null }
            .maxWithOrNull { a, b -> compare(parts(a.version)!!, parts(b.version)!!) }
    }

    /** Whether [candidate] is a later version than [current]. A version that cannot be read is never later. */
    fun isNewer(current: String, candidate: String): Boolean {
        val a = parts(current) ?: return false
        val b = parts(candidate) ?: return false
        return compare(b, a) > 0
    }

    /** The numbers of a version. A test version such as 0.7.1-rc1 has none, so it is never offered. */
    private fun parts(version: String): List<Int>? =
        version.removePrefix("v").takeIf { '-' !in it && '+' !in it }?.split('.')?.map { it.toIntOrNull() ?: return null }?.takeIf { it.isNotEmpty() }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val difference = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }
}
