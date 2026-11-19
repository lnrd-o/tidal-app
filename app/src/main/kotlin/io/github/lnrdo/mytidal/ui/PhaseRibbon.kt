package io.github.lnrdo.mytidal.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.lnrdo.mytidal.R
import io.github.lnrdo.mytidal.core.dj.Cue
import io.github.lnrdo.mytidal.core.dj.CueKind
import io.github.lnrdo.mytidal.core.dj.PhaseLevel
import io.github.lnrdo.mytidal.core.dj.SongPhases

/** The name of a phase of a song. */
@StringRes
fun phaseName(kind: CueKind): Int = when (kind) {
    CueKind.CHORUS -> R.string.phase_chorus
    CueKind.VERSE -> R.string.phase_verse
    CueKind.BRIDGE -> R.string.phase_bridge
    CueKind.DROP -> R.string.phase_drop
    CueKind.BUILD -> R.string.phase_build
    CueKind.BREAK -> R.string.phase_break
    CueKind.PEAK -> R.string.phase_peak
    CueKind.OUTRO -> R.string.phase_outro
    else -> R.string.phase_intro
}

/**
 * The song as a ribbon of phases over its seek line. Each phase is a block as long as the part of the song it covers: the energetic ones are tall and in the
 * accent colour, the building ones are half as tall, and the soft ones are low and faint. The phase that plays now is the brightest of its kind.
 */
@Composable
fun PhaseRibbon(cues: List<Cue>, duration: Long, position: Long, accent: Color, modifier: Modifier = Modifier) {
    val phases = remember(cues, duration) { SongPhases.build(cues, duration) }
    if (phases.isEmpty()) return
    Canvas(modifier) {
        val edge = 5.dp.toPx()
        val span = size.width - 2 * edge
        val gap = 1.5.dp.toPx()
        val base = 12.dp.toPx() // the ribbon stands on this line, above the seek line
        val now = SongPhases.at(phases, position)
        for (phase in phases) {
            val x0 = edge + phase.startMs.toFloat() / duration * span
            val x1 = edge + phase.endMs.toFloat() / duration * span
            val height = when (phase.level) { PhaseLevel.ENERGETIC -> 9.dp.toPx(); PhaseLevel.RISING -> 5.dp.toPx(); PhaseLevel.SOFT -> 2.5.dp.toPx() }
            val lit = phase === now
            val color = when (phase.level) {
                PhaseLevel.ENERGETIC -> accent.copy(alpha = if (lit) 1f else .75f)
                PhaseLevel.RISING -> Color.White.copy(alpha = if (lit) .85f else .5f)
                PhaseLevel.SOFT -> Color.White.copy(alpha = if (lit) .6f else .28f)
            }
            drawRect(color, Offset(x0, base - height), Size((x1 - x0 - gap).coerceAtLeast(1f), height))
        }
    }
}
