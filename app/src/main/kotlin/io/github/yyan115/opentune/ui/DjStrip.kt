package io.github.yyan115.opentune.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yyan115.opentune.R
import io.github.yyan115.opentune.core.dj.MixCurves
import io.github.yyan115.opentune.core.dj.MixKind
import io.github.yyan115.opentune.core.dj.MixPlanner
import io.github.yyan115.opentune.core.formatDuration
import io.github.yyan115.opentune.playback.dj.DjMix
import io.github.yyan115.opentune.playback.dj.DjPhase
import io.github.yyan115.opentune.playback.dj.DjUi
import kotlin.math.abs
import kotlin.math.floor

private const val ROWS_PER_LANE = 4

/**
 * The transition, drawn as two lanes of square cells: the outgoing track above and the incoming track below, each with its low, mid and high band.
 * A column is one beat. The brightness of a cell is the gain of that band at that moment, so the picture shows the bass staying out
 * until the swap, the mids and highs rising, and the outgoing track leaving. The fourth row of each lane is the filter: the low-pass that opens on the
 * incoming track, and the high-pass that closes in on the outgoing one. While the mix runs, a cursor moves across it.
 */
@Composable
fun DjStrip(modifier: Modifier = Modifier) {
    val ui by DjMix.ui.collectAsStateWithLifecycle()
    if (ui.phase == DjPhase.OFF) return
    // The colour tells how good the mix is: white when it is not a really good one, cyan when it is good, gold when it is almost perfect.
    val tint by animateColorAsState(matchColor(ui.match ?: ui.changedMatch), tween(300), label = "djai-tint")
    Column(modifier.fillMaxWidth().background(tint.copy(alpha = .08f)).border(1.dp, tint.copy(alpha = .45f), RectangleShape).padding(12.dp).testTag("djai-strip")) {
        DjHeader(ui, tint)
        if (ui.phase != DjPhase.WAITING) {
            Spacer(Modifier.height(10.dp))
            DjLanes(ui, tint)
        }
    }
}

@Composable
private fun DjHeader(ui: DjUi, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.djai_name), Modifier.background(tint).padding(horizontal = 6.dp, vertical = 2.dp),
            fontFamily = AppFonts.body, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = Color.Black)
        Spacer(Modifier.width(10.dp))
        Text(when (ui.phase) {
            DjPhase.WAITING -> if (ui.album) stringResource(R.string.djai_album)
                else if (ui.scoutTotal > 0 && ui.scouted < ui.scoutTotal) stringResource(R.string.djai_scouting, ui.scouted, ui.scoutTotal) else stringResource(R.string.djai_waiting)
            DjPhase.READING -> stringResource(R.string.djai_reading, ui.nextTitle.orEmpty())
            DjPhase.PLANNED -> stringResource(R.string.djai_planned, formatDuration(ui.startsInMs))
            DjPhase.MIXING -> stringResource(R.string.djai_mixing)
            DjPhase.OFF -> ""
        }, Modifier.weight(1f), fontFamily = AppFonts.body, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ui.kind?.let { Text(stringResource(if (it == MixKind.BEAT) R.string.djai_beat else R.string.djai_soft), fontFamily = AppFonts.body, fontSize = 11.sp, color = tint.copy(alpha = .6f)) }
    }
    val tempo = listOfNotNull(ui.bpmOut?.let { stringResource(R.string.djai_bpm, "%.1f".format(it)) }, ui.bpmIn?.let { stringResource(R.string.djai_bpm, "%.1f".format(it)) })
    val keys = listOfNotNull(ui.keyOut, ui.keyIn)
    val energy = listOfNotNull(ui.energyOut, ui.energyIn).map { "%.0f".format(it) }
    if (tempo.isNotEmpty() || keys.isNotEmpty() || energy.isNotEmpty()) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (tempo.isNotEmpty()) {
            // Each tempo blinks at its own beats per minute, so that two songs that do not agree can be seen to disagree.
            val bpms = listOfNotNull(ui.bpmOut, ui.bpmIn)
            Row(verticalAlignment = Alignment.CenterVertically) {
                tempo.forEachIndexed { index, text ->
                    if (index > 0) Text("  →  ", fontFamily = AppFonts.mono, fontSize = 11.sp, color = tint.copy(alpha = .8f))
                    Text(text, Modifier.beatPulse(bpms.getOrNull(index)), fontFamily = AppFonts.mono, fontSize = 11.sp, color = tint.copy(alpha = .8f))
                }
            }
        }
        if (tempo.isNotEmpty() && keys.isNotEmpty()) Spacer(Modifier.width(14.dp))
        if (keys.isNotEmpty()) Text(keys.joinToString("  →  ") { keyWithNote(it) }, fontFamily = AppFonts.mono, fontSize = 11.sp, color = tint.copy(alpha = .8f))
        if (ui.keyOut != null && ui.keyIn != null) {
            Spacer(Modifier.width(10.dp))
            Text(stringResource(if (ui.keyScore >= .8) R.string.djai_keys_match else if (ui.keyScore >= .5) R.string.djai_keys_near else R.string.djai_keys_clash),
                fontFamily = AppFonts.body, fontSize = 10.sp, color = tint.copy(alpha = .55f))
        }
        if (energy.isNotEmpty()) {
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.djai_energy, energy.joinToString(" → ")), fontFamily = AppFonts.mono, fontSize = 11.sp, color = tint.copy(alpha = .8f))
        }
        if (ui.kind == MixKind.BEAT && abs(ui.speed - 1.0) > .0005) {
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.djai_speed, "%+.1f".format((ui.speed - 1.0) * 100)), fontFamily = AppFonts.mono, fontSize = 10.sp, color = tint.copy(alpha = .55f))
        }
    }
    ui.changed?.let { title ->
        Text(stringResource(R.string.djai_changed, title, "%.0f".format((ui.changedMatch ?: 0.0) * 100)), Modifier.padding(top = 6.dp), fontFamily = AppFonts.body, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (ui.effects.isNotEmpty()) Text(stringResource(R.string.djai_effects, ui.effects), Modifier.padding(top = 4.dp), fontFamily = AppFonts.body, fontSize = 10.sp, letterSpacing = 1.2.sp, color = tint.copy(alpha = .8f))
    ui.match?.let { match ->
        Text(stringResource(R.string.djai_match, "%.0f".format(match * 100)), Modifier.padding(top = 4.dp), fontFamily = AppFonts.body, fontSize = 10.sp, color = tint.copy(alpha = .55f))
    }
}

@Composable
private fun DjLanes(ui: DjUi, tint: Color) {
    val columns = ui.beats.coerceIn(8, 64)
    var frame by remember { mutableIntStateOf(0) }
    // The cursor needs a frame clock only while the mix runs. The canvas reads the value in its draw scope, so nothing recomposes.
    LaunchedEffect(ui.phase) { if (ui.phase == DjPhase.MIXING) while (true) withFrameNanos { frame++ } }
    val gains = remember { DoubleArray(6) }
    val labelWidth = 22.dp
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(labelWidth).height(LANES_HEIGHT), verticalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.djai_lane_out), fontFamily = AppFonts.mono, fontSize = 8.sp, color = tint.copy(alpha = .55f))
            Text(stringResource(R.string.djai_lane_in), fontFamily = AppFonts.mono, fontSize = 8.sp, color = tint.copy(alpha = .55f))
        }
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(LANES_HEIGHT).testTag("djai-lanes")) {
                frame // Redraws on each frame while the mix runs.
                val running = ui.phase == DjPhase.MIXING
                val progress = if (running) DjMix.mixer.progress.toDouble() else -1.0
                val cursor = if (running) floor(progress * columns).toInt().coerceIn(0, columns - 1) else -1
                val rows = ROWS_PER_LANE * 2
                val gap = 1.5.dp.toPx()
                val laneGap = 6.dp.toPx()
                val pitch = size.width / columns
                val cellW = pitch - gap
                val cellH = (size.height - laneGap - gap * (rows - 2)) / rows
                for (column in 0 until columns) {
                    val u = (column + 0.5) / columns
                    MixCurves.gains(u, ui.swap, gains)
                    val past = column < cursor
                    val fade = if (past) .45f else 1f
                    for (row in 0 until rows) {
                        val lane = row / ROWS_PER_LANE
                        val band = row % ROWS_PER_LANE
                        val y = row * (cellH + gap) + if (lane == 1) laneGap - gap else 0f
                        val topLeft = Offset(column * pitch + gap / 2, y)
                        if (band == ROWS_PER_LANE - 1) {
                            // The filter row, outlined so that it reads apart from the bands: how far the filter has closed in on the sound.
                            val amount = if (lane == 0) MixCurves.highpassClosed(u, ui.swap) else 1.0 - MixCurves.lowpassOpen(u, ui.swap)
                            drawRect(tint.copy(alpha = (0.08f + 0.92f * amount.toFloat()) * fade), topLeft, Size(cellW, cellH), style = Stroke(width = 1.dp.toPx()))
                        } else {
                            // Rows run high to low inside a lane, as on a mixer: high at the top, low below it.
                            val gain = gains[lane * 3 + (2 - band)].toFloat().coerceIn(0f, 1f)
                            drawRect(tint.copy(alpha = (0.1f + 0.9f * gain) * fade), topLeft, Size(cellW, cellH))
                        }
                    }
                }
                if (cursor >= 0) drawRect(tint, Offset(cursor * pitch, 0f), Size(pitch, size.height), style = Stroke(width = 1.5.dp.toPx()))
                val swapX = (columns * ui.swap).toFloat() * pitch
                drawRect(tint.copy(alpha = .65f), Offset(swapX - .5.dp.toPx(), 0f), Size(1.dp.toPx(), size.height))
            }
            Text(stringResource(R.string.djai_swap), Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center,
                fontFamily = AppFonts.mono, fontSize = 8.sp, letterSpacing = 1.sp, color = tint.copy(alpha = .55f))
        }
    }
}

private val LANES_HEIGHT = 92.dp

private val Gold = Color(0xFFE3B341)
private val Cyan = Color(0xFF22D3EE)

/** White below 70 percent, cyan from 70 percent, gold from 90 percent. Without a match yet it is white. */
fun matchColor(match: Double?): Color = when {
    match == null -> Color.White
    match >= MixPlanner.PERFECT_MATCH -> Gold
    match >= MixPlanner.MIN_MATCH -> Cyan
    else -> Color.White
}
