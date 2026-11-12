package io.github.yyan115.opentune.ui

import io.github.yyan115.opentune.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import io.github.yyan115.opentune.core.CarouselModule
import io.github.yyan115.opentune.core.PageData
import io.github.yyan115.opentune.core.Release
import io.github.yyan115.opentune.core.Track

@UnstableApi
@Composable
fun ListenScreen(state: AppState, vm: MainViewModel, onLogin: () -> Unit,
    onLibrary: (Int) -> Unit, onDevice: () -> Unit, onMenu: (Track) -> Unit, onPlayer: () -> Unit) {
    fun available(track: Track) = track.onDevice || state.local.any { it.id == track.id }
    val recent = if (state.settings.offline) state.recent.filter(::available) else state.recent
    val current = state.playback.track?.takeIf { !state.settings.offline || available(it) }
    val hero = current ?: recent.firstOrNull()
    val heroTracks = recent.ifEmpty { listOfNotNull(hero) }
    val heroIndex = heroTracks.indexOfFirst { it.id == hero?.id }.coerceAtLeast(0)
    fun openHero() {
        if (hero != null && current?.id != hero.id) vm.play(heroTracks, heroIndex)
        onPlayer()
    }
    val pages: PagesViewModel = viewModel()
    val entries by pages.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = PageContext(state, vm, pages, scope, onMenu, stringResource(R.string.nav_home))
    val mixesTitle = stringResource(R.string.home_mixes)
    val online = state.signedIn && !state.settings.offline
    LaunchedEffect(online) { if (online) { pages.load(PageRoute.ForYou); pages.load(PageRoute.Home) } }
    val forYou = entries[PageRoute.ForYou.key]?.takeIf { online }
    val home = entries[PageRoute.Home.key]?.takeIf { online }
    PullToRefreshBox(isRefreshing = (forYou?.loading == true || home?.loading == true) && forYou?.page != null,
        onRefresh = { if (online) { pages.load(PageRoute.ForYou, true); pages.load(PageRoute.Home, true); vm.sync() } }, modifier = Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        // AI Smart Flow Recommendation Stream
        item {
            Card(
                onClick = { vm.startAiFlow(); onPlayer() },
                shape = androidx.compose.ui.graphics.RectangleShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = androidx.compose.ui.graphics.RectangleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(OpenTuneIcons.AutoAwesome, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.ai_flow_title), style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.badge_lossless),
                                modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), androidx.compose.ui.graphics.RectangleShape).padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            stringResource(R.string.ai_flow_subtitle),
                            Modifier.padding(top = 3.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(OpenTuneIcons.PlayArrow, stringResource(R.string.ai_flow_start), Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (hero != null) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    UpperLabel(stringResource(R.string.home_continue), Modifier.weight(1f))
                    if (available(hero)) Icon(OpenTuneIcons.DownloadDone, stringResource(R.string.track_available_offline), Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val configuration = LocalConfiguration.current
                val wide = configuration.screenWidthDp > configuration.screenHeightDp
                val quality = state.playback.quality.takeIf { current?.id == hero.id }.orEmpty()
                val playing = current?.id == hero.id && state.playback.playing
                val buffering = current?.id == hero.id && state.playback.buffering
                val toggle = { if (current?.id == hero.id) vm.togglePlay() else vm.play(heroTracks, heroIndex) }
                if (wide) Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    ListeningArtwork(hero, quality, Modifier.width(240.dp), 1.5f, ::openHero)
                    ListeningTitle(hero, playing, buffering, Modifier.weight(1f), toggle)
                } else {
                    ListeningArtwork(hero, quality, Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        if (configuration.screenHeightDp < 700) 1.9f else 1.6f, ::openHero)
                    ListeningTitle(hero, playing, buffering, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), toggle)
                }
            }
        } else {
            if (!state.signedIn && !state.settings.offline) item { ConnectCard(onLogin) }
            else if (!(state.signedIn && !state.settings.offline)) item { EmptyState(OpenTuneIcons.Album, stringResource(R.string.home_empty_title), stringResource(R.string.home_empty_text)) }
        }
        forYou?.page?.let { pageContent(it.tidied(mixesTitle), forYou, PageRoute.ForYou.key, context, viewAll = false) }
        if (state.albums.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.home_saved_albums), stringResource(R.string.action_view_all), { onLibrary(1) }) }
            item { LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.albums.take(12), key = Release::id) { release -> ReleaseCard(release) { vm.open(release) } }
            } }
        }
        val others = recent.filterNot { it.id == hero?.id }.take(4)
        if (others.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.home_recently_played)) }
            items(others, key = { "recent:${it.id}" }) { track -> TrackRow(track, false,
                { vm.play(recent, recent.indexOf(track)) }, { onMenu(track) }) }
        }
        home?.page?.let { pageContent(it.tidied(mixesTitle), home, PageRoute.Home.key, context, viewAll = false) }
        val local = state.local.filterNot { it.id == hero?.id }.take(3)
        if (local.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.nav_device), stringResource(R.string.action_view_all), onDevice) }
            items(local, key = { "device:${it.id}" }) { track -> TrackRow(track, false,
                { vm.play(state.local, state.local.indexOf(track)) }, { onMenu(track) }) }
        }
        if (hero != null && !state.signedIn && !state.settings.offline) item { Spacer(Modifier.height(16.dp)); ConnectCard(onLogin) }
        if (online && forYou?.page == null && home?.page == null) item(key = "pages-state") {
            val error = forYou?.error ?: home?.error
            if (error != null) ErrorCard(error) { pages.load(PageRoute.ForYou, true); pages.load(PageRoute.Home, true) }
            else Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 1.5.dp) }
        }
    }
    }
}

/**
 * Merges adjacent carousels that share a title and renames TIDAL's generic section names.
 * The rename matches the English title. In other languages TIDAL's own translated title stays.
 */
private fun PageData.tidied(mixesTitle: String): PageData {
    val result = mutableListOf<io.github.yyan115.opentune.core.PageModule>()
    for (module in modules) {
        val previous = result.lastOrNull()
        if (module is CarouselModule && previous is CarouselModule && previous.title == module.title && previous.layout == module.layout) {
            result[result.lastIndex] = previous.copy(items = previous.items + module.items, total = previous.total + module.total)
        } else result += module
    }
    return copy(modules = result.map { module ->
        if (module is CarouselModule && module.title == "Custom mixes") module.copy(title = mixesTitle) else module
    })
}


@Composable
private fun ListeningArtwork(track: Track, quality: String, modifier: Modifier, ratio: Float, onClick: () -> Unit) {
    Box(modifier.clickable(onClick = onClick)) {
        TrackArtwork(track, stringResource(R.string.player_artwork, track.album.ifBlank { track.title }), Modifier.fillMaxWidth().aspectRatio(ratio))
        if (quality.isNotBlank()) Surface(Modifier.align(Alignment.TopStart).padding(12.dp),
            color = androidx.compose.ui.graphics.Color(0xE0121213), shape = androidx.compose.ui.graphics.RectangleShape) {
            val readout = SourceReadout.from(quality)
            Text(listOfNotNull(readout.codec.ifBlank { null }, readout.bitsText(), readout.rateText()).joinToString(" · "), Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                fontFamily = AppFonts.body, fontSize = 9.sp, lineHeight = 14.sp, color = androidx.compose.ui.graphics.Color(0xFFF1EBDD))
        }
    }
}

@Composable
private fun ListeningTitle(track: Track, playing: Boolean, buffering: Boolean, modifier: Modifier, onPlay: () -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 14.dp)) {
            Text(track.title, style = AppFonts.title(36, 40), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.artist, Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PlayControl(playing, buffering, size = 54.dp, onClick = onPlay)
    }
}
