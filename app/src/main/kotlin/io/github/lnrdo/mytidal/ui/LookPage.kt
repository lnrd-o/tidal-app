package io.github.lnrdo.mytidal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lnrdo.mytidal.R

/** The look of the app: the design of the player, the way that the lyrics are shown, and the style of the spectrum. Each choice acts at once. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun LookPage(state: AppState, vm: MainViewModel, onEffects: () -> Unit = {}) {
    val settings = state.settings
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionTitle(stringResource(R.string.look_section_mode)) }
        for ((id, name, hint) in listOf(Triple("system", R.string.look_mode_system, R.string.look_mode_system_text), Triple("light", R.string.look_mode_light, R.string.look_mode_light_text),
            Triple("dark", R.string.look_mode_dark, R.string.look_mode_dark_text))) item(key = "mode-$id") {
            ChoiceRow(stringResource(name), stringResource(hint), settings.appearance == id) { vm.settings { it.copy(appearance = id) } }
        }
        item { SectionTitle(stringResource(R.string.fx_title)) }
        item { SettingRow(OpenTuneIcons.Contrast, stringResource(R.string.fx_title), stringResource(R.string.fx_summary), onEffects, trailing = { androidx.compose.material3.Icon(OpenTuneIcons.ChevronRight, null) }) }
        item { SectionTitle(stringResource(R.string.look_section_player)) }
        item { SwitchRow(OpenTuneIcons.AutoAwesome, stringResource(R.string.look_rounded), settings.rounded, { value -> vm.settings { it.copy(rounded = value) } }, stringResource(R.string.look_rounded_text)) }
        item { SectionTitle(stringResource(R.string.look_section_lyrics)) }
        for (style in LyricStyle.entries) item(key = "lyric-${style.id}") {
            ChoiceRow(stringResource(style.nameRes), stringResource(style.hintRes), settings.lyricStyle == style.id) { vm.settings { it.copy(lyricStyle = style.id) } }
        }
        item { SectionTitle(stringResource(R.string.look_section_spectrum)) }
        item { Text(stringResource(R.string.look_spectrum_tap), Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        for (style in SpectrumStyle.entries) item(key = "spectrum-${style.id}") {
            ChoiceRow(stringResource(style.nameRes), stringResource(style.hintRes), settings.spectrumStyle == style.id) { vm.settings { it.copy(spectrumStyle = style.id) } }
        }
    }
}

@Composable
private fun ChoiceRow(title: String, hint: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onSelect)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
