package io.github.lnrdo.mytidal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lnrdo.mytidal.R
import io.github.lnrdo.mytidal.data.Settings
import io.github.lnrdo.mytidal.playback.dj.DjMix

/** The ways to ask online catalogues for the tempo and key of a song, and what each one says. */
private val OnlineModes = intArrayOf(R.string.settings_online_off, R.string.settings_online_tidal, R.string.settings_online_deezer)
private val OnlineHints = intArrayOf(R.string.settings_online_off_text, R.string.settings_online_tidal_text, R.string.settings_online_deezer_text)

/** The words that sum up D'JAI in the list of settings: off, or on with the number of songs that it has kept. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun djaiSummary(settings: Settings): String {
    val kept by DjMix.known.collectAsStateWithLifecycle()
    return if (settings.djai) stringResource(R.string.djcfg_summary_on, kept.size) else stringResource(R.string.settings_off)
}

/**
 * All the settings of D'JAI in one page: the automix itself, each effect of the transitions, how the next song is chosen, and what is kept about songs and
 * shown in the lists. Each one acts at once.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun DjaiPage(state: AppState, vm: MainViewModel) {
    val settings = state.settings
    val kept by DjMix.known.collectAsStateWithLifecycle()
    var online by remember { mutableStateOf(false) }
    var forget by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionTitle(stringResource(R.string.djcfg_section_mix)) }
        item { SwitchRow(OpenTuneIcons.Waves, stringResource(R.string.settings_djai), settings.djai, { value -> vm.settings { it.copy(djai = value) } }, stringResource(R.string.settings_djai_text)) }

        item { SectionTitle(stringResource(R.string.djcfg_section_transitions)) }
        item { SwitchRow(OpenTuneIcons.Tune, stringResource(R.string.djcfg_filters), settings.djFilters, { value -> vm.settings { it.copy(djFilters = value) } }, stringResource(R.string.djcfg_filters_text)) }
        item { SwitchRow(OpenTuneIcons.Tune, stringResource(R.string.djcfg_cues), settings.djCues, { value -> vm.settings { it.copy(djCues = value) } }, stringResource(R.string.djcfg_cues_text)) }

        item { SectionTitle(stringResource(R.string.djcfg_section_choice)) }
        item { SwitchRow(OpenTuneIcons.Waves, stringResource(R.string.djcfg_groove), settings.djGroove, { value -> vm.settings { it.copy(djGroove = value) } }, stringResource(R.string.djcfg_groove_text)) }
        item { SwitchRow(OpenTuneIcons.Waves, stringResource(R.string.djcfg_beatfirst), settings.djBeatFirst, { value -> vm.settings { it.copy(djBeatFirst = value) } }, stringResource(R.string.djcfg_beatfirst_text)) }
        item { SwitchRow(OpenTuneIcons.Waves, stringResource(R.string.djcfg_revalidate), settings.djRevalidate, { value -> vm.settings { it.copy(djRevalidate = value) } }, stringResource(R.string.djcfg_revalidate_text)) }
        item { SwitchRow(OpenTuneIcons.Waves, stringResource(R.string.djcfg_arc), settings.djArc, { value -> vm.settings { it.copy(djArc = value) } }, stringResource(R.string.djcfg_arc_text)) }
        item {
            ValueRow(stringResource(R.string.djcfg_min_match, settings.djMinMatch), stringResource(R.string.djcfg_min_match_text), settings.djMinMatch, 50..90, 7) { value ->
                vm.settings { it.copy(djMinMatch = value) }
            }
        }
        item {
            ValueRow(stringResource(R.string.djcfg_ahead, settings.djListenAhead), stringResource(R.string.djcfg_ahead_text), settings.djListenAhead, 4..16, 5) { value ->
                vm.settings { it.copy(djListenAhead = value) }
            }
        }

        item { SectionTitle(stringResource(R.string.djcfg_section_speed)) }
        item { SwitchRow(OpenTuneIcons.Waves, stringResource(R.string.djcfg_genre_ai), settings.djGenreAi, { value -> vm.settings { it.copy(djGenreAi = value) } }, stringResource(R.string.djcfg_genre_ai_text)) }
        if (settings.djGenreAi) item { GenreGpuRow(settings) { f -> vm.settings(f) } }
        item { NeuralPanel(settings) { f -> vm.settings(f) } }
        item { SwitchRow(OpenTuneIcons.Waves, stringResource(R.string.djcfg_fast), settings.djFastAnalysis, { value -> vm.settings { it.copy(djFastAnalysis = value) } }, stringResource(R.string.djcfg_fast_text)) }
        item {
            ValueRow(stringResource(R.string.djcfg_workers, settings.djWorkers), stringResource(R.string.djcfg_workers_text, Runtime.getRuntime().availableProcessors()), settings.djWorkers, 1..3, 1) { value ->
                vm.settings { it.copy(djWorkers = value) }
            }
        }
        item { SwitchRow(OpenTuneIcons.Info, stringResource(R.string.djcfg_load_overlay), settings.djLoadOverlay, { value -> vm.settings { it.copy(djLoadOverlay = value) } }, stringResource(R.string.djcfg_load_overlay_text)) }
        item { HardwarePanel() }
        item {
            val context = androidx.compose.ui.platform.LocalContext.current
            SettingRow(OpenTuneIcons.Refresh, stringResource(R.string.hw_reset), stringResource(R.string.hw_reset_text), { Thread { val profile = HardwareInfo.profile(context.applicationContext); vm.settings { it.withRecommendation(profile) } }.start() })
        }

        item { SectionTitle(stringResource(R.string.djcfg_section_songs)) }
        item { SwitchRow(OpenTuneIcons.Waves, stringResource(R.string.settings_dj_data), settings.showDjData, { value -> vm.settings { it.copy(showDjData = value) } }, stringResource(R.string.settings_dj_data_text)) }
        item { SwitchRow(OpenTuneIcons.Waves, stringResource(R.string.djcfg_pulse), settings.djPulse, { value -> vm.settings { it.copy(djPulse = value) } }, stringResource(R.string.djcfg_pulse_text)) }
        item { SettingRow(OpenTuneIcons.Waves, stringResource(R.string.settings_online), stringResource(OnlineModes[settings.onlineData.coerceIn(0, 2)]), { online = true }) }
        item { SettingRow(OpenTuneIcons.Info, stringResource(R.string.djcfg_kept), stringResource(R.string.djcfg_kept_value, kept.size)) }
        item { SettingRow(OpenTuneIcons.RemoveCircleOutline, stringResource(R.string.djcfg_forget), stringResource(R.string.djcfg_forget_text), { forget = true }) }
    }
    if (online) AlertDialog(onDismissRequest = { online = false }, title = { Text(stringResource(R.string.settings_online)) }, text = {
        Column {
            Text(stringResource(R.string.settings_online_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (mode in OnlineModes.indices) Row(Modifier.fillMaxWidth().clickable { vm.settings { it.copy(onlineData = mode) } }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(settings.onlineData == mode, { vm.settings { it.copy(onlineData = mode) } })
                Column(Modifier.padding(start = 8.dp)) {
                    Text(stringResource(OnlineModes[mode]), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(OnlineHints[mode]), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { online = false }) { Text(stringResource(R.string.action_close)) } })
    if (forget) AlertDialog(onDismissRequest = { forget = false }, title = { Text(stringResource(R.string.djcfg_forget)) }, text = { Text(stringResource(R.string.djcfg_forget_confirm)) },
        confirmButton = { TextButton(onClick = { vm.forgetDjai(); forget = false }) { Text(stringResource(R.string.djcfg_forget_action)) } },
        dismissButton = { TextButton(onClick = { forget = false }) { Text(stringResource(R.string.action_cancel)) } })
}

/** A number in a range, set with a slider that takes [steps] stops between its ends. The value is kept when the finger lifts. */
@Composable
private fun ValueRow(title: String, hint: String, value: Int, range: IntRange, steps: Int, onChange: (Int) -> Unit) {
    var dragging by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Slider(dragging, { dragging = it }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = steps,
            onValueChangeFinished = { onChange(Math.round(dragging)) })
        Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
