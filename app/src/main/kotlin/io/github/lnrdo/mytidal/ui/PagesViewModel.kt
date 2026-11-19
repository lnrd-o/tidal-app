package io.github.lnrdo.mytidal.ui

import io.github.lnrdo.mytidal.problemText
import io.github.lnrdo.mytidal.R
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.lnrdo.mytidal.OpenTuneApp
import io.github.lnrdo.mytidal.core.CarouselLayout
import io.github.lnrdo.mytidal.core.CarouselModule
import io.github.lnrdo.mytidal.core.PageCodec
import io.github.lnrdo.mytidal.core.PageData
import io.github.lnrdo.mytidal.core.PageModule
import io.github.lnrdo.mytidal.core.Release
import io.github.lnrdo.mytidal.core.ReleaseType
import io.github.lnrdo.mytidal.core.ServiceException
import io.github.lnrdo.mytidal.core.Track
import io.github.lnrdo.mytidal.core.TrackListModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class PageUi(
    val page: PageData? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val loadedAt: Long = 0,
    /** Items appended to a module after the first response, keyed by module id. */
    val moreReleases: Map<String, List<Release>> = emptyMap(),
    val moreTracks: Map<String, List<Track>> = emptyMap(),
    val loadingMore: Set<String> = emptySet(),
)

/** Loads and caches TIDAL pages. The cache shows the last response at once while a fresh one loads. */
@androidx.media3.common.util.UnstableApi
class PagesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as OpenTuneApp
    private val mutable = MutableStateFlow<Map<String, PageUi>>(emptyMap())
    val state: StateFlow<Map<String, PageUi>> = mutable.asStateFlow()

    private fun update(key: String, transform: (PageUi) -> PageUi) = mutable.update { it + (key to transform(it[key] ?: PageUi())) }

    fun load(route: PageRoute, force: Boolean = false) {
        val key = route.key
        val current = mutable.value[key]
        if (current?.loading == true) return
        if (!force && current?.page != null && System.currentTimeMillis() - current.loadedAt < FRESH_MS) return
        update(key) { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val account = app.tidal.accountId
            if (current?.page == null) {
                val cached = withContext(Dispatchers.IO) { app.library.cache(account, "page:$key") }
                cached?.let { runCatching { PageCodec.parse(JSONObject(it)) }.getOrNull() }?.let { page -> update(key) { it.copy(page = page) } }
            }
            val offline = app.settings.state.value.offline
            if (offline || !app.tidal.signedIn) {
                update(key) { it.copy(loading = false, error = if (it.page == null) app.getString(if (offline) R.string.page_error_offline else R.string.page_error_signed_out) else null) }
                return@launch
            }
            try {
                val json = app.tidal.pageJson(route.path, route.query)
                val page = PageCodec.parse(json)
                withContext(Dispatchers.IO) {
                    app.library.cache(account, "page:$key", json.toString())
                    app.library.pruneCache("page:", MAX_CACHED_PAGES)
                }
                update(key) { PageUi(page = page, loadedAt = System.currentTimeMillis()) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                update(key) { it.copy(loading = false, error = if (it.page == null) app.pageError(error) else null) }
            }
        }
    }

    fun loadMore(routeKey: String, module: PageModule) {
        val path = when (module) { is CarouselModule -> module.dataPath; is TrackListModule -> module.dataPath; else -> null } ?: return
        val entry = mutable.value[routeKey] ?: return
        if (module.id in entry.loadingMore) return
        update(routeKey) { it.copy(loadingMore = it.loadingMore + module.id) }
        viewModelScope.launch {
            try {
                when (module) {
                    is CarouselModule -> {
                        val shown = module.items.size + (entry.moreReleases[module.id]?.size ?: 0)
                        val next = PageCodec.releases(app.tidal.moduleItems(path, shown), module.layout, layoutKind(module.layout))
                        update(routeKey) { it.copy(moreReleases = it.moreReleases + (module.id to (it.moreReleases[module.id].orEmpty() + next))) }
                    }
                    is TrackListModule -> {
                        val shown = module.tracks.size + (entry.moreTracks[module.id]?.size ?: 0)
                        val next = PageCodec.tracks(app.tidal.moduleItems(path, shown))
                        update(routeKey) { it.copy(moreTracks = it.moreTracks + (module.id to (it.moreTracks[module.id].orEmpty() + next))) }
                    }
                    else -> Unit
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            } finally { update(routeKey) { it.copy(loadingMore = it.loadingMore - module.id) } }
        }
    }

    /** Every track of a module, fetching the pages that have not been loaded yet. */
    suspend fun allTracks(routeKey: String, module: TrackListModule): List<Track> {
        val loaded = module.tracks + mutable.value[routeKey]?.moreTracks?.get(module.id).orEmpty()
        val path = module.dataPath ?: return loaded
        if (loaded.size >= module.total) return loaded
        val result = loaded.toMutableList()
        try {
            while (result.size < module.total && result.size < MAX_PLAY_ALL) {
                val next = PageCodec.tracks(app.tidal.moduleItems(path, result.size, 100))
                if (next.isEmpty()) break
                result += next
            }
        } catch (error: Exception) { if (error is CancellationException) throw error }
        return result.distinctBy { it.id }
    }

    private fun layoutKind(layout: CarouselLayout) = when (layout) {
        CarouselLayout.ALBUMS, CarouselLayout.MIXED -> ReleaseType.ALBUM
        CarouselLayout.PLAYLISTS -> ReleaseType.PLAYLIST
        CarouselLayout.MIXES -> ReleaseType.MIX
        CarouselLayout.ARTISTS -> ReleaseType.ARTIST
        CarouselLayout.VIDEOS -> ReleaseType.VIDEO
    }

    private companion object {
        const val FRESH_MS = 5 * 60_000L
        const val MAX_CACHED_PAGES = 60
        const val MAX_PLAY_ALL = 500
    }
}

internal fun android.content.Context.pageError(error: Throwable): String = when (error) {
    is ServiceException, is io.github.lnrdo.mytidal.core.ProtectedStreamException -> problemText(error)
    is java.io.IOException -> getString(R.string.page_error_unreachable)
    else -> getString(R.string.page_error_generic)
}
