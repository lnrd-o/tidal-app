package io.github.lnrdo.mytidal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import io.github.lnrdo.mytidal.R
import io.github.lnrdo.mytidal.core.Track
import io.github.lnrdo.mytidal.playback.AudioSources
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The songs that the cache of listened songs holds, most recently heard first, each with the space it takes. A song can be saved to the device (it is then
 * downloaded and stays) or removed from the cache. The songs that are not saved are the ones that the cache lets go of first when it is full.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun CachePage(state: AppState, vm: MainViewModel) {
    var songs by remember { mutableStateOf<List<Pair<AudioSources.CachedSong, Track?>>?>(null) }
    var total by remember { mutableLongStateOf(0L) }
    var version by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(version) { songs = vm.cachedSongs(); total = vm.cacheBytes() }
    // The cache grows while a song plays, so the list is read again now and then.
    LaunchedEffect(Unit) { while (true) { delay(5_000); songs = vm.cachedSongs(); total = vm.cacheBytes() } }
    val list = songs
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text(stringResource(R.string.cache_used, formatSize(total), if (state.settings.cacheMb == 0) stringResource(R.string.settings_off) else formatSize(state.settings.cacheMb.toLong() * 1024 * 1024), list?.size ?: 0),
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (list != null && list.isEmpty()) item { EmptyState(OpenTuneIcons.LibraryMusic, stringResource(R.string.cache_title), stringResource(R.string.cache_empty)) }
        items(list.orEmpty(), key = { it.first.id }) { (song, track) ->
            val saved = track?.onDevice == true
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (track != null) TrackArtwork(track, null, Modifier.size(48.dp)) else Spacer(Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track?.title ?: stringResource(R.string.cache_unknown_song), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(listOfNotNull(track?.artist?.takeIf { it.isNotBlank() }, formatSize(song.bytes)).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (track != null) {
                    if (saved) Text(stringResource(R.string.cache_saved), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 4.dp))
                    else IconButton({ vm.download(track) }) { Icon(OpenTuneIcons.Download, stringResource(R.string.cache_save)) }
                }
                IconButton({ vm.removeCachedSong(song.id); scope.launch { delay(300); version++ } }) { Icon(OpenTuneIcons.RemoveCircleOutline, stringResource(R.string.cache_remove)) }
            }
        }
        item {
            TextButton({ scope.launch { vm.clearCache(); version++ } }, Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.cache_clear_all)) }
        }
    }
}
