package io.github.lnrdo.mytidal.ui

/** A TIDAL page that can be opened: an API path plus query parameters. */
data class PageRoute(val path: String, val query: Map<String, String> = emptyMap(), val title: String = "") {
    val key: String get() = path + query.entries.sortedBy { it.key }.joinToString("&", if (query.isEmpty()) "" else "?") { "${it.key}=${it.value}" }

    companion object {
        fun artist(id: String, title: String = "") = PageRoute("pages/artist", mapOf("artistId" to id), title)
        fun album(id: String, title: String = "") = PageRoute("pages/album", mapOf("albumId" to id), title)
        fun mix(id: String, title: String = "") = PageRoute("pages/mix", mapOf("mixId" to id), title)
        fun path(path: String, title: String = "") = PageRoute(path, emptyMap(), title)

        // The titles of these routes are shown by their screens, which translate them.
        val ForYou = path("pages/for_you")
        val Home = path("pages/home")
        val Explore = path("pages/explore")
    }
}
