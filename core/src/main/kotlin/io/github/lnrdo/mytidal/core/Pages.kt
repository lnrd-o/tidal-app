package io.github.lnrdo.mytidal.core

import org.json.JSONObject

/** A TIDAL page: an ordered list of modules, as returned by the `pages/...` endpoints. */
data class PageData(val id: String, val title: String, val modules: List<PageModule>)

sealed interface PageModule { val id: String; val title: String }

enum class CarouselLayout { ALBUMS, PLAYLISTS, MIXES, ARTISTS, VIDEOS, MIXED }

data class CarouselModule(
    override val id: String, override val title: String, val layout: CarouselLayout, val items: List<Release>,
    val showMore: String? = null, val dataPath: String? = null, val total: Int = items.size,
) : PageModule

data class TrackListModule(
    override val id: String, override val title: String, val tracks: List<Track>, val numbered: Boolean = false,
    val showMore: String? = null, val dataPath: String? = null, val total: Int = tracks.size,
) : PageModule

data class PageLink(val title: String, val path: String)
data class LinksModule(override val id: String, override val title: String, val links: List<PageLink>, val cloud: Boolean) : PageModule

data class ArtistHeader(
    override val id: String, override val title: String, val artist: Release, val bio: String, val bioSource: String, val radioId: String,
) : PageModule

data class AlbumHeader(
    override val id: String, override val title: String, val album: Release, val artistName: String, val artistId: String,
    val releaseDate: String, val copyright: String, val trackCount: Int, val durationMs: Long, val tags: List<String>,
    val review: String, val reviewSource: String, val explicit: Boolean,
) : PageModule

data class MixHeader(override val id: String, override val title: String, val mix: Release, val subtitle: String, val description: String) : PageModule
data class TextModule(override val id: String, override val title: String, val text: String) : PageModule

object PageCodec {
    fun parse(root: JSONObject): PageData {
        val modules = mutableListOf<PageModule>()
        val rows = root.optJSONArray("rows")
        if (rows != null) for (rowIndex in 0 until rows.length()) {
            val list = rows.optJSONObject(rowIndex)?.optJSONArray("modules") ?: continue
            for (index in 0 until list.length()) list.optJSONObject(index)?.let(::module)?.let(modules::add)
        }
        return PageData(root.optString("id"), root.optString("title"), modules)
    }

    /** Removes TIDAL's inline link markup from biographies and reviews. */
    fun plainText(markup: String?): String {
        if (markup.isNullOrBlank() || markup == "null") return ""
        return markup
            .replace(Regex("""\[wimpLink [^\]]*](.*?)\[/wimpLink]""", RegexOption.DOT_MATCHES_ALL), "$1")
            .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("""</?[a-zA-Z][^>]*>"""), "")
            .replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    private fun module(json: JSONObject): PageModule? {
        val type = json.optString("type")
        val id = json.optString("id")
        val title = json.optString("title").takeIf { it != "null" }.orEmpty()
        val paged = json.optJSONObject("pagedList")
        val showMore = json.optJSONObject("showMore")?.optString("apiPath")?.takeIf { it.isNotBlank() && it != "null" }
        val dataPath = paged?.optString("dataApiPath")?.takeIf { it.isNotBlank() && it != "null" }
        val total = paged?.optInt("totalNumberOfItems", -1) ?: -1
        fun carousel(layout: CarouselLayout, kind: ReleaseType): PageModule? {
            val items = releases(paged, layout, kind)
            return if (items.isEmpty()) null else CarouselModule(id, title, layout, items, showMore, dataPath, if (total >= 0) total else items.size)
        }
        return when (type) {
            "ALBUM_LIST" -> carousel(CarouselLayout.ALBUMS, ReleaseType.ALBUM)
            "PLAYLIST_LIST" -> carousel(CarouselLayout.PLAYLISTS, ReleaseType.PLAYLIST)
            "MIX_LIST" -> carousel(CarouselLayout.MIXES, ReleaseType.MIX)
            "ARTIST_LIST" -> carousel(CarouselLayout.ARTISTS, ReleaseType.ARTIST)
            "VIDEO_LIST" -> carousel(CarouselLayout.VIDEOS, ReleaseType.VIDEO)
            "MIXED_TYPES_LIST" -> carousel(CarouselLayout.MIXED, ReleaseType.ALBUM)
            "TRACK_LIST", "ALBUM_ITEMS", "ITEM_LIST_WITH_ROLES" -> {
                val tracks = tracks(paged)
                if (tracks.isEmpty()) null else TrackListModule(id, title, tracks, numbered = type == "ALBUM_ITEMS",
                    showMore = showMore, dataPath = dataPath, total = if (total >= 0) total else tracks.size)
            }
            "PAGE_LINKS_CLOUD", "PAGE_LINKS" -> {
                val items = paged?.optJSONArray("items")
                val links = if (items == null) emptyList() else (0 until items.length()).mapNotNull { index ->
                    items.optJSONObject(index)?.let { link ->
                        val path = link.optString("apiPath")
                        val name = link.optString("title")
                        if (path.isBlank() || name.isBlank()) null else PageLink(name, path)
                    }
                }
                if (links.isEmpty()) null else LinksModule(id, title, links, type == "PAGE_LINKS_CLOUD")
            }
            "ARTIST_HEADER" -> json.optJSONObject("artist")?.let { artist ->
                val bio = json.optJSONObject("bio")
                ArtistHeader(id, title, JsonCodec.release(artist, ReleaseType.ARTIST), plainText(bio?.optString("text")),
                    bio?.optString("source")?.takeIf { it != "null" }.orEmpty(),
                    (json.optJSONObject("artistMix")?.optString("id") ?: json.optJSONObject("mixes")?.optString("ARTIST_MIX")).orEmpty().takeIf { it != "null" }.orEmpty())
            }
            "ALBUM_HEADER" -> json.optJSONObject("album")?.let { album ->
                val release = JsonCodec.release(album, ReleaseType.ALBUM)
                val review = json.optJSONObject("review")
                AlbumHeader(id, title, release, release.artist, release.artistId, album.optString("releaseDate").takeIf { it != "null" }.orEmpty(),
                    album.optString("copyright").takeIf { it != "null" }.orEmpty(), album.optInt("numberOfTracks"), album.optLong("duration") * 1000,
                    release.tags, plainText(review?.optString("text")), review?.optString("source")?.takeIf { it != "null" }.orEmpty(), album.optBoolean("explicit"))
            }
            "MIX_HEADER" -> json.optJSONObject("mix")?.let { mix ->
                MixHeader(id, title, JsonCodec.release(mix, ReleaseType.MIX), mix.optString("subTitle").takeIf { it != "null" }.orEmpty(),
                    mix.optString("description").takeIf { it != "null" }.orEmpty())
            }
            "TEXT_BLOCK" -> json.optString("text").takeIf { it.isNotBlank() && it != "null" }?.let { TextModule(id, title, plainText(it)) }
            else -> null
        }
    }

    /** Parses the items of a paged list. [fallback] applies to items that do not name their own type. */
    fun releases(paged: JSONObject?, layout: CarouselLayout, fallback: ReleaseType): List<Release> {
        val array = paged?.optJSONArray("items") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val element = array.optJSONObject(index) ?: return@mapNotNull null
            val wrapped = element.has("item") && element.has("type")
            val body = if (wrapped) element.optJSONObject("item") ?: return@mapNotNull null else element
            val kind = if (layout == CarouselLayout.MIXED) when (element.optString("type")) {
                "PLAYLIST" -> ReleaseType.PLAYLIST; "ALBUM" -> ReleaseType.ALBUM; "ARTIST" -> ReleaseType.ARTIST
                "MIX" -> ReleaseType.MIX; "VIDEO" -> ReleaseType.VIDEO; else -> return@mapNotNull null
            } else fallback
            JsonCodec.release(body, kind).takeIf { it.id.isNotBlank() }
        }
    }

    fun tracks(paged: JSONObject?): List<Track> = if (paged == null) emptyList() else JsonCodec.items(paged).mapNotNull { item ->
        if (item.has("id") && item.has("title") && (item.has("duration") || item.has("artists"))) JsonCodec.track(item) else null
    }
}
