package io.github.lnrdo.mytidal.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lnrdo.mytidal.R
import io.github.lnrdo.mytidal.core.formatDuration
import io.github.lnrdo.mytidal.playback.dj.DjMix
import io.github.lnrdo.mytidal.playback.dj.DjPhase
import io.github.lnrdo.mytidal.playback.dj.neural.Accelerator
import io.github.lnrdo.mytidal.playback.dj.neural.BeatModel

/**
 * D'JAI in a sheet of its own, opened from its button on the player, so that the player stays clean: the switch, what D'JAI is doing now with the picture of
 * the transition, where the rhythm model runs, and its question about the tempo of the song.
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun DjaiSheet(on: Boolean, neural: Boolean, trackId: String?, cues: List<io.github.lnrdo.mytidal.core.dj.Cue>, duration: Long, onSwitch: (Boolean) -> Unit, onClose: () -> Unit) {
    val ui by DjMix.ui.collectAsStateWithLifecycle()
    val model by BeatModel.state.collectAsStateWithLifecycle()
    ModalBottomSheet(shape = SheetShape, dragHandle = { SheetHandle() }, onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DjaiMark(46.dp, Modifier.padding(end = 14.dp), live = on, tint = MaterialTheme.colorScheme.onSurface)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.djai_name), style = AppFonts.title(34, 38), color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(if (on) R.string.djsheet_on else R.string.djsheet_off), Modifier.padding(top = 2.dp), fontFamily = AppFonts.body, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
                }
                Switch(on, onSwitch)
            }
            Spacer(Modifier.height(16.dp))
            if (!on) {
                Text(stringResource(R.string.settings_djai_text), fontFamily = AppFonts.body, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
                return@Column
            }
            // Facts in one line: how good the coming mix is, and where the rhythm model runs.
            Row(verticalAlignment = Alignment.CenterVertically) {
                val match = ui.match
                Fact(stringResource(R.string.djsheet_match), if (match != null) "%.0f%%".format(match * 100) else "—", matchColor(match, MaterialTheme.colorScheme.onSurface))
                Spacer(Modifier.width(20.dp))
                Fact(stringResource(R.string.djsheet_next), if (ui.phase == DjPhase.PLANNED) formatDuration(ui.startsInMs) else if (ui.phase == DjPhase.MIXING) stringResource(R.string.djai_mixing) else "—", MaterialTheme.colorScheme.onSurface)
                if (neural) {
                    Spacer(Modifier.width(20.dp))
                    val where = model.loaded
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Fact(stringResource(R.string.djsheet_model), where?.name ?: "—", MaterialTheme.colorScheme.onSurface)
                        if (where != null) {
                            Spacer(Modifier.width(6.dp))
                            androidx.compose.foundation.layout.Box(Modifier.size(8.dp).background(when (where) { Accelerator.NPU -> Color(0xFFD6B56A); Accelerator.GPU -> Color(0xFF3B82F6); else -> MaterialTheme.colorScheme.onSurface }))
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // The style of the song that plays and of the one that comes, and the part of the song that plays now.
            var picking by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val outId = ui.idOut ?: trackId
                Fact(stringResource(R.string.djsheet_style), ui.genreOut?.let { stringResource(genreName(it)) } ?: stringResource(R.string.djsheet_unknown), MaterialTheme.colorScheme.onSurface,
                    Modifier.clickable(enabled = outId != null) { picking = outId })
                if (ui.genreIn != null || ui.nextTitle != null) {
                    Spacer(Modifier.width(10.dp))
                    Text("→", fontFamily = AppFonts.mono, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
                    Spacer(Modifier.width(10.dp))
                    Fact(stringResource(R.string.djsheet_next_style), ui.genreIn?.let { stringResource(genreName(it)) } ?: stringResource(R.string.djsheet_unknown), MaterialTheme.colorScheme.onSurface,
                        Modifier.clickable(enabled = ui.idIn != null) { picking = ui.idIn })
                }
            }
            picking?.let { id -> GenrePicker(id, if (id == ui.idIn) ui.genreIn else ui.genreOut) { picking = null } }
            val position by LocalPlaybackPosition.current.collectAsStateWithLifecycle()
            val phases = androidx.compose.runtime.remember(cues, duration) { io.github.lnrdo.mytidal.core.dj.SongPhases.build(cues, duration) }
            if (phases.isNotEmpty()) {
                val now = io.github.lnrdo.mytidal.core.dj.SongPhases.at(phases, position)
                Spacer(Modifier.height(14.dp))
                Fact(stringResource(R.string.djsheet_part), now?.let { stringResource(phaseName(it.kind)) } ?: "—", MaterialTheme.colorScheme.onSurface)
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(20.dp).padding(top = 6.dp)) { PhaseRibbon(cues, duration, position, Color(0xFFD6B56A), Modifier.matchParentSize()) }
            }
            Spacer(Modifier.height(16.dp))
            DjStrip()
            if (neural && trackId != null) RhythmConfirmCard(trackId, Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun Fact(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label.uppercase(), fontFamily = AppFonts.body, fontSize = 9.sp, letterSpacing = 1.4.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f))
        Text(value, fontFamily = AppFonts.mono, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = tint)
    }
}
