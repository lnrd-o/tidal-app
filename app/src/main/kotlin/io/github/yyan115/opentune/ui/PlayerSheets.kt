package io.github.yyan115.opentune.ui

import io.github.yyan115.opentune.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay

/** Minutes left on the sleep timer, refreshed every 20 seconds. Null when no timed sleep timer runs. */
@Composable
fun rememberSleepMinutes(endsAt: Long): State<Int?> = produceState<Int?>(null, endsAt) {
    while (endsAt > 0) {
        value = ((endsAt - System.currentTimeMillis() + 59_999) / 60_000).toInt().coerceAtLeast(0)
        delay(20_000)
    }
    value = null
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun SleepTimerSheet(state: AppState, vm: MainViewModel, onClose: () -> Unit) {
    val remaining by rememberSleepMinutes(state.sleepEndsAt)
    val active = state.sleepEndsAt > 0 || state.sleepAtTrackEnd
    ModalBottomSheet(shape = androidx.compose.ui.graphics.RectangleShape, dragHandle = { SheetHandle() }, onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.sleep_title), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall)
            Text(when {
                state.sleepAtTrackEnd -> stringResource(R.string.sleep_status_track_end)
                state.sleepEndsAt > 0 -> stringResource(R.string.sleep_status_timed, remaining ?: 0)
                else -> stringResource(R.string.sleep_status_idle)
            }, Modifier.padding(horizontal = 24.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(15, 30, 45, 60, 90).forEach { minutes ->
                SettingRow(OpenTuneIcons.Timer, pluralStringResource(R.plurals.sleep_minutes, minutes, minutes), onClick = { vm.setSleepTimer(minutes); onClose() })
            }
            SettingRow(OpenTuneIcons.SkipNext, stringResource(R.string.sleep_track_end), onClick = { vm.setSleepTimer(0, endOfTrack = true); onClose() })
            if (active) SettingRow(OpenTuneIcons.Close, stringResource(R.string.sleep_turn_off), onClick = { vm.setSleepTimer(0); onClose() })
        }
    }
}

@Composable
fun NormalizationDialog(current: String, onPick: (String) -> Unit, onClose: () -> Unit) {
    val options = listOf(
        Triple("off", stringResource(R.string.normalization_off), stringResource(R.string.normalization_off_hint)),
        Triple("track", stringResource(R.string.normalization_track), stringResource(R.string.normalization_track_hint)),
        Triple("album", stringResource(R.string.normalization_album), stringResource(R.string.normalization_album_hint)),
    )
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.normalization_title)) }, text = {
        Column {
            options.forEach { (value, label, description) ->
                Row(Modifier.fillMaxWidth().clickable { onPick(value); onClose() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(current == value, onClick = null)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(stringResource(R.string.normalization_note), Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } })
}
