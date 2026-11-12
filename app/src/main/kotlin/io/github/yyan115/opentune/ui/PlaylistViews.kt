package io.github.yyan115.opentune.ui

import io.github.yyan115.opentune.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.yyan115.opentune.core.Release
import io.github.yyan115.opentune.core.ReleaseType
import io.github.yyan115.opentune.core.Track
import io.github.yyan115.opentune.core.isDevicePlaylist
import kotlinx.coroutines.delay

/** The playlists of the listener as one list: playlists of the account, which carry no mark, and playlists made on the device, which carry the Local mark. */
@UnstableApi
fun playlistReleases(state: AppState, vm: MainViewModel): List<Release> =
    (state.playlists.filter(vm::canEdit) + state.localPlaylists.map(::deviceRelease)).sortedBy { it.title.lowercase() }

/** A playlist made on the device, as a release that the screens can open. */
fun deviceRelease(playlist: io.github.yyan115.opentune.core.LocalPlaylist) =
    Release(playlist.id, playlist.title, "", playlist.artwork, subtitle = playlist.trackIds.size.toString(), type = ReleaseType.PLAYLIST)

/** A playlist in a list. A playlist made on the device carries the Local mark. */
@Composable
fun PlaylistListRow(playlist: Release, trailing: @Composable () -> Unit = { Icon(OpenTuneIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 24.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (playlist.isDevicePlaylist && playlist.artwork == null) Surface(Modifier.size(56.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Box(contentAlignment = Alignment.Center) { Icon(OpenTuneIcons.PlaylistPlay, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) }
        } else Artwork(playlist.artwork, null, Modifier.size(56.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(playlist.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (playlist.isDevicePlaylist) {
                    LocalBadge(Modifier.padding(end = 6.dp))
                    val count = playlist.subtitle.toIntOrNull() ?: 0
                    Text(pluralStringResource(R.plurals.track_count, count, count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Text(playlist.artist.ifBlank { playlist.subtitle }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

/** Where a new playlist is kept. */
enum class PlaylistHome { Account, Device }

/** Asks for the name of a new playlist and, when an account is connected, where to keep it. */
@Composable
fun NewPlaylistDialog(accountAvailable: Boolean, onCreate: (String, PlaylistHome) -> Unit, onClose: () -> Unit, initialName: String = "") {
    var name by remember { mutableStateOf(initialName) }
    var home by remember { mutableStateOf(if (accountAvailable) PlaylistHome.Account else PlaylistHome.Device) }
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.playlist_new)) }, text = {
        Column {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.field_name)) }, singleLine = true)
            if (accountAvailable) {
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(home == PlaylistHome.Account, { home = PlaylistHome.Account }, label = { Text(stringResource(R.string.playlist_home_account)) })
                    FilterChip(home == PlaylistHome.Device, { home = PlaylistHome.Device }, label = { Text(stringResource(R.string.label_local)) })
                }
                if (home == PlaylistHome.Device) Text(stringResource(R.string.playlist_home_device_text), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), home) }) { Text(stringResource(R.string.action_create)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } })
}

/** Lists the playlists that a track can join. A file on the device can join playlists made on the device only. */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun AddToPlaylistSheet(track: Track, state: AppState, vm: MainViewModel, onNew: () -> Unit, onClose: () -> Unit) {
    val playlists = playlistReleases(state, vm).filter { !track.isFile || it.isDevicePlaylist }
    ModalBottomSheet(shape = androidx.compose.ui.graphics.RectangleShape, dragHandle = { SheetHandle() }, onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item { Text(stringResource(R.string.playlist_add), Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.titleLarge) }
            item { SettingRow(OpenTuneIcons.Add, stringResource(R.string.playlist_new), onClick = onNew) }
            items(playlists, key = { it.id }) { playlist -> PlaylistListRow(playlist, trailing = {}) { vm.addToPlaylist(playlist, track); onClose() } }
            if (playlists.isEmpty()) item { Text(stringResource(R.string.playlist_none), Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * Adds tracks to a playlist without leaving it. The sheet searches TIDAL and the files on the device, and lists the saved tracks
 * while the search is empty. A track that the playlist holds shows a check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun AddSongsSheet(playlist: Release, state: AppState, vm: MainViewModel, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val online = state.signedIn && !state.settings.offline
    val inPlaylist = state.detailTracks.map(Track::id).toSet()
    val remote by produceState(emptyList<Track>(), text, online) {
        value = emptyList()
        if (text.isBlank() || !online) return@produceState
        delay(350)
        value = vm.searchTracks(text)
    }
    val local = remember(state.local, text) { if (text.isBlank()) emptyList() else state.local.filter { it.title.contains(text, true) || it.artist.contains(text, true) || it.album.contains(text, true) } }
    val results = (if (text.isBlank()) state.favorites + state.local else local + remote).distinctBy(Track::id).filter { playlist.isDevicePlaylist || !it.isFile }
    ModalBottomSheet(shape = androidx.compose.ui.graphics.RectangleShape, dragHandle = { SheetHandle() }, onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.playlist_add_songs), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_done)) }
            }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).testTag("add-songs-search"), singleLine = true,
                placeholder = { Text(stringResource(R.string.search_hint)) }, leadingIcon = { Icon(OpenTuneIcons.Search, null) },
                trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(OpenTuneIcons.Close, stringResource(R.string.search_clear)) } },
                shape = MaterialTheme.shapes.large, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }))
            if (text.isBlank()) UpperLabel(stringResource(R.string.nav_library), Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
                items(results, key = { it.id }) { track ->
                    val added = track.id in inPlaylist
                    Row(Modifier.fillMaxWidth().clickable(enabled = !added) { vm.addToPlaylist(playlist, track, quiet = true) }.padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TrackArtwork(track, null, Modifier.size(44.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (track.isFile) LocalBadge(Modifier.padding(end = 6.dp))
                                Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = { vm.addToPlaylist(playlist, track, quiet = true) }, enabled = !added) {
                            Icon(if (added) OpenTuneIcons.Check else OpenTuneIcons.Add, stringResource(if (added) R.string.playlist_song_added else R.string.playlist_add_song, track.title),
                                tint = if (added) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                if (results.isEmpty()) item { Text(stringResource(if (text.isBlank()) R.string.playlist_add_empty else R.string.search_no_tracks), Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
