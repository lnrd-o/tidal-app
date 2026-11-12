package io.github.yyan115.opentune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.work.WorkInfo
import io.github.yyan115.opentune.R
import io.github.yyan115.opentune.core.Track
import io.github.yyan115.opentune.downloadErrorText

/** The tracks that were downloaded from TIDAL, and the downloads that are running or waiting. */
@UnstableApi
@Composable
fun DownloadsScreen(state: AppState, vm: MainViewModel, onLogin: () -> Unit, onMenu: (Track) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val active = state.downloads.filter { !it.state.isFinished || it.state == WorkInfo.State.FAILED }
    val query = text.trim()
    val shown = remember(state.local, query) {
        if (query.isEmpty()) state.local else state.local.filter { it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) }
    }
    val sourceLabel = stringResource(R.string.nav_device)
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().testTag("device-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (!state.signedIn) item { ConnectCard(onLogin) }
        if (active.isNotEmpty()) item { SectionTitle(stringResource(R.string.device_downloading)) }
        items(active, key = { it.id }) { work ->
            val trackId = work.tags.firstOrNull { it.startsWith("track:") }?.removePrefix("track:")
            val track = state.downloadTracks[trackId]
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(OpenTuneIcons.Download, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(track?.title ?: stringResource(R.string.downloads_unnamed), style = MaterialTheme.typography.titleSmall)
                        Text(when (work.state) {
                            WorkInfo.State.RUNNING -> stringResource(R.string.download_progress, work.progress.getInt("progress", 0))
                            WorkInfo.State.FAILED -> context.downloadErrorText(work.outputData.getString("error"))
                            WorkInfo.State.BLOCKED -> stringResource(R.string.download_waiting)
                            else -> if (state.settings.downloadOnWifi) stringResource(R.string.download_queued_wifi) else stringResource(R.string.download_queued)
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!work.state.isFinished) IconButton(onClick = { vm.cancelDownload(work) }) { Icon(OpenTuneIcons.Close, stringResource(R.string.download_cancel)) }
                    else if (track != null) TextButton(onClick = { vm.download(track) }) { Text(stringResource(R.string.action_retry)) }
                }
                if (work.state == WorkInfo.State.RUNNING) LinearProgressIndicator(progress = { work.progress.getInt("progress", 0) / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
        }
        if (state.downloads.any { it.state.isFinished }) item { TextButton(onClick = vm::clearDownloadHistory, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.downloads_clear)) } }
        if (state.local.isEmpty()) {
            if (active.isEmpty()) item { Box(Modifier.fillParentMaxHeight(.5f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(OpenTuneIcons.Download, stringResource(R.string.device_empty_title), stringResource(R.string.device_empty_text))
            } }
        } else {
            item(key = "tools") {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), singleLine = true, placeholder = { Text(stringResource(R.string.library_filter)) },
                    leadingIcon = { Icon(OpenTuneIcons.Search, null, Modifier.size(18.dp)) }, shape = MaterialTheme.shapes.large,
                    trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(OpenTuneIcons.Close, stringResource(R.string.library_filter_clear), Modifier.size(18.dp)) } })
            }
            item { SectionTitle(pluralStringResource(R.plurals.track_count, shown.size, shown.size), if (shown.size > 1) stringResource(R.string.action_shuffle) else null) { vm.playTracks(shown, shuffle = true, source = sourceLabel) } }
            items(shown, key = Track::id) { track -> TrackRow(track, state.playback.track?.id == track.id, { vm.play(shown, shown.indexOf(track), sourceLabel) }, { onMenu(track) }) }
            if (shown.isEmpty()) item { Text(stringResource(R.string.device_filter_empty), Modifier.padding(horizontal = 24.dp, vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
