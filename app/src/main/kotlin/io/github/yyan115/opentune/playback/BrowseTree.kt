package io.github.yyan115.opentune.playback

import io.github.yyan115.opentune.R
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import io.github.yyan115.opentune.OpenTuneApp
import io.github.yyan115.opentune.core.JsonCodec
import io.github.yyan115.opentune.core.Release
import io.github.yyan115.opentune.core.ReleaseType
import io.github.yyan115.opentune.core.Track

/**
 * The library that cars and Bluetooth devices can browse: recent plays, saved tracks, albums,
 * playlists and downloads. Lists come from the device cache first, so browsing works without a connection.
 */
@UnstableApi
class BrowseTree(private val app: OpenTuneApp) {
    fun root(): MediaItem = folder(ROOT, app.getString(R.string.app_name))

    fun isFolder(id: String) = id == ROOT || id in TOP || id in LIBRARY_PARTS || id.startsWith("album:") || id.startsWith("playlist:") || id.startsWith("lp:")

    fun item(id: String): MediaItem? = when {
        id == ROOT -> root()
        id in TOP || id in LIBRARY_PARTS -> tab(id)
        id.startsWith("album:") || id.startsWith("playlist:") || id.startsWith("lp:") -> folder(id, id.substringAfter(':'))
        else -> app.library.get(id)?.let(app.sources::unresolved)
    }

    suspend fun children(parentId: String): List<MediaItem> = when {
        // A car shows the first four folders of the root as tabs, so the root has four, each with its icon.
        parentId == ROOT -> TOP.map(::tab)
        parentId == LIBRARY -> LIBRARY_PARTS.map(::tab)
        parentId == RECENT -> tracks(app.library.recent())
        parentId == FAVORITES -> tracks((app.library.favorites() + cached("favorites")).distinctBy(Track::id))
        parentId == DOWNLOADS -> tracks(app.library.localTracks())
        parentId == ALBUMS -> releases("albums", "album")
        parentId == PLAYLISTS -> app.library.playlists().map { folder("lp:${it.id}", it.title, app.resources.getQuantityString(R.plurals.track_count, it.trackIds.size, it.trackIds.size)) } + releases("playlists", "playlist")
        parentId.startsWith("album:") -> {
            val id = parentId.removePrefix("album:")
            tracks(if (online()) runCatching { app.tidal.allTracks(Release(id, "", "", type = ReleaseType.ALBUM)) }.getOrDefault(emptyList())
                else app.library.localTracks().filter { it.albumId == id })
        }
        parentId.startsWith("playlist:") -> tracks(if (online()) runCatching { app.tidal.allTracks(Release(parentId.removePrefix("playlist:"), "", "", type = ReleaseType.PLAYLIST)) }.getOrDefault(emptyList()) else emptyList())
        parentId.startsWith("lp:") -> tracks(app.library.playlistTracks(parentId.removePrefix("lp:")))
        else -> emptyList()
    }

    suspend fun search(query: String): List<MediaItem> {
        val local = (app.library.localTracks() + app.library.favorites() + app.library.recent()).filter {
            it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
        }
        val remote = if (online()) runCatching { app.tidal.search(query).tracks }.getOrDefault(emptyList()) else emptyList()
        return tracks((local + remote).distinctBy(Track::id).take(MAX_SEARCH_RESULTS))
    }

    /** Replaces a bare request from a controller by the full queue item. */
    fun resolve(item: MediaItem): MediaItem {
        if (item.localConfiguration != null) return item
        return app.library.get(item.mediaId)?.let(app.sources::unresolved) ?: item
    }

    private fun topTitle(id: String): String = app.getString(when (id) {
        RECENT -> R.string.home_recently_played
        FAVORITES -> R.string.library_saved_tracks
        ALBUMS -> R.string.label_albums
        PLAYLISTS -> R.string.label_playlists
        LIBRARY -> R.string.car_tab_library
        else -> R.string.nav_device
    })

    /** A folder of the root or of the library, with its icon, and the way that its content is shown: albums and playlists as a grid, songs as a list. */
    private fun tab(id: String): MediaItem {
        val icon = when (id) {
            RECENT -> R.drawable.ic_tab_recent
            FAVORITES -> R.drawable.ic_tab_saved
            PLAYLISTS -> R.drawable.ic_tab_playlists
            else -> R.drawable.ic_tab_library
        }
        val grid = id == ALBUMS || id == PLAYLISTS || id == LIBRARY
        return folder(id, topTitle(id), artwork = "android.resource://${app.packageName}/$icon", grid = grid)
    }

    private fun online() = app.tidal.signedIn && !app.settings.state.value.offline

    private fun cached(kind: String): List<Track> = app.library.cache(app.tidal.accountId, kind)?.let { runCatching { JsonCodec.decodeTracks(it) }.getOrNull() }.orEmpty()

    private fun releases(kind: String, prefix: String): List<MediaItem> =
        app.library.cache(app.tidal.accountId, kind)?.let { runCatching { JsonCodec.decodeReleases(it) }.getOrNull() }.orEmpty().map {
            folder("$prefix:${it.id}", it.title, it.artist.ifBlank { null }, it.artwork)
        }

    private fun tracks(list: List<Track>): List<MediaItem> {
        val capped = list.take(MAX_TRACKS)
        app.library.saveAll(capped)
        return capped.map(app.sources::unresolved)
    }

    private fun folder(id: String, title: String, subtitle: String? = null, artwork: String? = null, grid: Boolean = false) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setArtworkUri(artwork?.let(Uri::parse)).setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).setExtras(contentStyle(grid)).build()).build()

    companion object {
        /** The hints that a car reads to show folders and songs as a grid or as a list. */
        private const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val STYLE_LIST = 1
        private const val STYLE_GRID = 2

        fun contentStyle(grid: Boolean) = android.os.Bundle().apply {
            putInt(STYLE_BROWSABLE, if (grid) STYLE_GRID else STYLE_LIST)
            putInt(STYLE_PLAYABLE, STYLE_LIST)
        }

        /** The extras of the root: the car may search, albums and playlists are a grid, and songs a list. */
        fun rootExtras() = contentStyle(true).apply { putBoolean("android.media.browse.SEARCH_SUPPORTED", true) }

        const val ROOT = "root"
        const val RECENT = "recent"
        const val FAVORITES = "favorites"
        const val ALBUMS = "albums"
        const val PLAYLISTS = "playlists"
        const val DOWNLOADS = "downloads"
        const val LIBRARY = "library"
        private val TOP = listOf(RECENT, FAVORITES, PLAYLISTS, LIBRARY)
        private val LIBRARY_PARTS = listOf(ALBUMS, DOWNLOADS)
        private const val MAX_TRACKS = 300
        private const val MAX_SEARCH_RESULTS = 30
    }
}
