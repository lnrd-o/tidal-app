package io.github.lnrdo.mytidal.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lnrdo.mytidal.R
import io.github.lnrdo.mytidal.playback.dj.neural.RhythmStore

/**
 * Asks the listener whether the rhythm model heard the song's tempo right: Right, half, double, or tapped. Each answer is kept and trains the model on the
 * phone. It shows once for each song that the model has read and that has no answer yet.
 */
@Composable
fun RhythmConfirmCard(trackId: String, modifier: Modifier = Modifier) {
    val songs by RhythmStore.songs.collectAsStateWithLifecycle()
    val entry = songs[trackId] ?: return
    if (entry.confirmedBpm != null) return
    var tapping by remember(trackId) { mutableStateOf(false) }
    val taps = remember(trackId) { mutableStateListOf<Long>() }
    val shown = RhythmStore.bpmFor(trackId) ?: entry.neuralBpm
    Column(modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .22f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(stringResource(R.string.rhythm_heard, "%.1f".format(shown), entry.beatsPerBar, entry.accelerator).uppercase(), fontFamily = AppFonts.mono, fontSize = 11.sp, letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .85f))
        if (!tapping) {
            Text(stringResource(R.string.rhythm_ask), Modifier.padding(top = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Choice(stringResource(R.string.rhythm_right)) { RhythmStore.answer(trackId, shown) }
                Choice(stringResource(R.string.rhythm_half)) { RhythmStore.answer(trackId, shown / 2) }
                Choice(stringResource(R.string.rhythm_double)) { RhythmStore.answer(trackId, shown * 2) }
                Choice(stringResource(R.string.rhythm_tap)) { tapping = true; taps.clear() }
            }
        } else {
            // Tap tempo: the median gap of the last taps, once there are at least four.
            val bpm = if (taps.size >= 4) taps.zipWithNext { a, b -> b - a }.sorted().let { 60_000.0 / it[it.size / 2] } else null
            Text(if (bpm == null) stringResource(R.string.rhythm_tap_more, (4 - taps.size).coerceAtLeast(0)) else stringResource(R.string.rhythm_tapped, "%.1f".format(bpm)),
                Modifier.padding(top = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.height(44.dp).weight(1f).border(1.dp, MaterialTheme.colorScheme.onSurface).clickable { taps.add(System.currentTimeMillis()); if (taps.size > 12) taps.removeAt(0) }, contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.rhythm_tap_here).uppercase(), fontSize = 12.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                }
                if (bpm != null) Choice(stringResource(R.string.rhythm_save)) { RhythmStore.answer(trackId, bpm); tapping = false }
                Choice(stringResource(R.string.action_cancel)) { tapping = false }
            }
        }
    }
}

@Composable
private fun Choice(label: String, onClick: () -> Unit) {
    Box(Modifier.border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .4f)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}
