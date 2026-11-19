package io.github.lnrdo.mytidal.core

import java.net.URLDecoder

/** Where a document lies on a storage volume, for display. [segments] are the folders and the file name in order. */
data class DocumentLocation(val volume: String, val segments: List<String>) {
    val folders get() = segments.dropLast(1)
    val fileName get() = segments.lastOrNull().orEmpty()
}

/**
 * Identifies a document of a system document provider independently of the route that led to it.
 * A file that the listener added directly and the same file found by a folder scan have different addresses, but the same key.
 */
object DocumentKey {
    private val document = Regex("^content://([^/]+)/(?:tree/[^/]+/)?document/([^/?#]+)")

    private fun decode(value: String) = runCatching { URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(value)

    /** The key of an address. An address that is not a document keeps its own text. */
    fun of(uri: String): String = document.find(uri)?.let { "${it.groupValues[1]}|${decode(it.groupValues[2])}" } ?: uri

    /** The volume and the path of a document on external storage, or null for a provider that does not use paths. */
    fun location(uri: String): DocumentLocation? {
        val match = document.find(uri) ?: return null
        val id = decode(match.groupValues[2])
        val volume = id.substringBefore(':', "")
        if (volume.isEmpty() || ':' !in id) return null
        return DocumentLocation(volume, id.substringAfter(':').split('/').filter(String::isNotEmpty))
    }
}
