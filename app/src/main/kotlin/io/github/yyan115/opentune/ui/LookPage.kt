package io.github.yyan115.opentune.ui

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
import io.github.yyan115.opentune.R

/** The look of the app: its theme, the way that the lyrics are shown, and the style of the spectrum. Each choice acts at once. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun LookPage(state: AppState, vm: MainViewModel) {
    val settings = state.settings
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionTitle(stringResource(R.string.look_section_theme)) }
        for (theme in appThemes) item(key = "theme-${theme.id}") {
            ChoiceRow(stringResource(theme.nameRes), stringResource(theme.hintRes), settings.appTheme == theme.id) { vm.settings { it.copy(appTheme = theme.id) } }
        }
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
