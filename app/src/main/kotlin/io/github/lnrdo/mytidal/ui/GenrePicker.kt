package io.github.lnrdo.mytidal.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lnrdo.mytidal.R
import io.github.lnrdo.mytidal.core.dj.MusicGenre
import io.github.lnrdo.mytidal.playback.dj.neural.GenreStore

/**
 * Lets the listener say what genre a song is. The answer is kept for the song, wins over the genre model and a catalogue, and teaches the model the genres of
 * the rest of the library, including those that no network knows by name, such as reggaeton.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenrePicker(trackId: String, current: MusicGenre?, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.genre_pick_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.genre_pick_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (genre in MusicGenre.entries) {
                    val on = genre == current
                    Box(Modifier.border(1.dp, if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .2f)).clickable { GenreStore.fromListener(trackId, genre); onClose() }.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Text(stringResource(genreName(genre)), fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } },
        dismissButton = if (GenreStore.labelOf(trackId)?.fromListener == true) { { TextButton(onClick = { GenreStore.forgetLabel(trackId); onClose() }) { Text(stringResource(R.string.genre_pick_forget)) } } } else null)
}
