package io.github.yyan115.opentune.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.yyan115.opentune.R
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** The pages that open from the Settings list. Each one has its own title in the top bar. */
enum class SettingsPage(@StringRes val title: Int) {
    Repeats(R.string.settings_repeats), Statistics(R.string.stats_title), Licenses(R.string.settings_licenses),
    Apache(R.string.licenses_apache),
    DmSans(R.string.licenses_dmsans), Mono(R.string.licenses_mono);

    /** The page that the back button returns to, or null for the Settings list. */
    val parent: SettingsPage? get() = if (this in LicenceDocuments) Licenses else null
}

/** How long a track stays out of shuffle, radio and autoplay after it played, in hours. Zero turns the setting off. */
private val RepeatWindows = listOf(0, 1, 6, 24, 168)

@Composable
fun repeatWindowText(hours: Int): String = when {
    hours % 24 == 0 -> pluralStringResource(R.plurals.window_days, hours / 24, hours / 24)
    else -> pluralStringResource(R.plurals.window_hours, hours, hours)
}

/** A span of time as days and hours, hours and minutes, or minutes. */
@Composable
fun durationText(milliseconds: Long): String {
    val minutes = (milliseconds.coerceAtLeast(0) / 60_000).toInt().coerceAtLeast(1)
    return when {
        minutes >= 24 * 60 -> stringResource(R.string.duration_days_hours, minutes / (24 * 60), minutes / 60 % 24)
        minutes >= 60 -> stringResource(R.string.stats_duration, minutes / 60, minutes % 60)
        else -> stringResource(R.string.duration_minutes, minutes)
    }
}

/**
 * The time window of the avoid-repeats setting and the tracks that the window excludes now.
 * Each excluded track shows when it played and how long it stays excluded, and can be included at once.
 */
@UnstableApi
@Composable
fun AvoidRepeatsPage(state: AppState, vm: MainViewModel) {
    val hours = state.settings.avoidRepeatHours
    val revision by vm.app.library.changes.collectAsStateWithLifecycle()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    val excluded by produceState<List<MainViewModel.ExcludedTrack>?>(null, hours, revision, now) { value = vm.excludedTracks(hours) }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().testTag("repeats-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text(stringResource(if (state.signedIn) R.string.settings_repeats_text_tidal else R.string.settings_repeats_text), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { UpperLabel(stringResource(R.string.repeats_window), Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) }
        items(RepeatWindows.size) { index ->
            val window = RepeatWindows[index]
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(hours == window, role = Role.RadioButton) { vm.settings { it.copy(avoidRepeatHours = window) } }
                .padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(hours == window, onClick = null)
                Text(if (window == 0) stringResource(R.string.settings_off) else repeatWindowText(window), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (hours > 0) {
            item { UpperLabel(stringResource(R.string.repeats_excluded), Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 8.dp)) }
            val tracks = excluded
            when {
                tracks == null -> item { CircularProgressIndicator(Modifier.padding(24.dp)) }
                tracks.isEmpty() -> item { Text(stringResource(R.string.repeats_none), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> items(tracks.size, key = { tracks[it].track.id }) { index ->
                    val entry = tracks[index]
                    val left = entry.playedAt + hours * 3_600_000L - now
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TrackArtwork(entry.track, null, Modifier.size(44.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(entry.track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(entry.track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.repeats_played, dateFormat.format(Date(entry.playedAt))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.repeats_available, durationText(left)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = { vm.allowAgain(entry.track) }) { Text(stringResource(R.string.repeats_include)) }
                    }
                }
            }
        }
    }
}

/** The plays, hours and most played tracks and artists of a period. */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun StatisticsPage(vm: MainViewModel) {
    var days by rememberSaveable { mutableIntStateOf(30) }
    var stats by remember { mutableStateOf<ListeningStats?>(null) }
    // The numbers of the earlier period stay on the screen while the next period loads, so a change of period does not flash.
    LaunchedEffect(days) { stats = vm.listeningStats(days) }
    val ranges = listOf(7 to pluralStringResource(R.plurals.stats_days, 7, 7), 30 to pluralStringResource(R.plurals.stats_days, 30, 30), 365 to stringResource(R.string.stats_year), 0 to stringResource(R.string.stats_all))
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        // A period is one choice of several, so the control is a single-choice segmented button, which fills the selected segment.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("stats-range")) {
            ranges.forEachIndexed { index, (value, label) ->
                SegmentedButton(days == value, { days = value }, SegmentedButtonDefaults.itemShape(index, ranges.size), icon = {},
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = MaterialTheme.colorScheme.primary, activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        activeBorderColor = MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 4.dp)) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val current = stats
            when {
                current == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                current.plays == 0 -> Text(stringResource(R.string.stats_empty), Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                    Text(stringResource(R.string.stats_summary, pluralStringResource(R.plurals.stats_listens, current.plays, current.plays), stringResource(R.string.stats_duration, current.hours.toInt(), current.minutes.toInt())), style = MaterialTheme.typography.titleMedium)
                    UpperLabel(stringResource(R.string.stats_top_tracks), Modifier.padding(top = 18.dp, bottom = 4.dp))
                    current.tracks.take(10).forEach { entry ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.track.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(entry.track.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(stringResource(R.string.stats_times, entry.plays), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    UpperLabel(stringResource(R.string.stats_top_artists), Modifier.padding(top = 18.dp, bottom = 4.dp))
                    current.artists.take(10).forEach { (name, plays) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.stats_times, plays), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
