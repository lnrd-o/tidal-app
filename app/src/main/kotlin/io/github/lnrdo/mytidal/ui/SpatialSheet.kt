package io.github.lnrdo.mytidal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lnrdo.mytidal.R
import io.github.lnrdo.mytidal.core.spatial.SpatialChannel
import io.github.lnrdo.mytidal.core.spatial.SpatialMode
import io.github.lnrdo.mytidal.core.spatial.meterLevel
import io.github.lnrdo.mytidal.playback.SpatialReport
import io.github.lnrdo.mytidal.playback.SpatialState
import io.github.lnrdo.mytidal.playback.SpatialSupport
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Spatial sound: the stereo of any song is spread over a surround layout (5.1 or 7.1) that the system's spatializer (Dolby Atmos on devices that have it)
 * places around the listener, on speakers or on headphones, Bluetooth ones included. It needs the device to spatialize on the present output, which the
 * sheet says. The graphic shows what sounds in each channel of the layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpatialSheet(state: AppState, vm: MainViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var report by remember { mutableStateOf(SpatialSupport.report(context)) }
    // The output changes when headphones are connected, and the user may turn the system's spatial audio on.
    LaunchedEffect(Unit) { while (true) { delay(1500); report = SpatialSupport.report(context) } }
    val selected = SpatialMode.of(state.settings.spatialMode)
    ModalBottomSheet(onClose, shape = SheetShape, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, dragHandle = { SheetHandle() }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.spatial_title), style = AppFonts.title(30, 34))
            Text(statusText(report), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected == null, { vm.settings { it.copy(spatialMode = "off") } }, { Text(stringResource(R.string.spatial_off)) })
                SpatialMode.entries.forEach { mode ->
                    FilterChip(selected == mode, { vm.settings { it.copy(spatialMode = mode.id) } }, { Text(stringResource(modeName(mode))) })
                }
            }
            Text(stringResource(selected?.let(::modeText) ?: R.string.spatial_off_text), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChannelGraphic(report, selected != null)
            if (selected != null && !report.usable) Text(stringResource(R.string.spatial_waiting), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun statusText(report: SpatialReport): String {
    val where = stringResource(when (report.output) { SpatialReport.Output.HEADPHONES -> R.string.spatial_out_headphones; SpatialReport.Output.SPEAKER -> R.string.spatial_out_speaker; else -> R.string.spatial_out_other })
    val dolby = if (report.dolby) stringResource(R.string.spatial_dolby) + " · " else ""
    return dolby + where + " · " + stringResource(when {
        report.usable -> if (report.channels == 8) R.string.spatial_ready_71 else R.string.spatial_ready_51
        report.available && !report.enabled -> R.string.spatial_system_off
        else -> R.string.spatial_unavailable
    })
}

private fun modeName(mode: SpatialMode) = when (mode) {
    SpatialMode.STAGE -> R.string.spatial_stage; SpatialMode.SURROUND -> R.string.spatial_surround; SpatialMode.IMMERSIVE -> R.string.spatial_immersive; SpatialMode.FOCUS -> R.string.spatial_focus
}

private fun modeText(mode: SpatialMode) = when (mode) {
    SpatialMode.STAGE -> R.string.spatial_stage_text; SpatialMode.SURROUND -> R.string.spatial_surround_text; SpatialMode.IMMERSIVE -> R.string.spatial_immersive_text; SpatialMode.FOCUS -> R.string.spatial_focus_text
}

/** Where each channel sits, seen from above with the listener in the middle and the front up: the angle from the front, clockwise, and the distance from the middle (0 to 1). */
private fun place(channel: SpatialChannel): Pair<Float, Float> = when (channel) {
    SpatialChannel.FRONT_LEFT -> -32f to .78f
    SpatialChannel.FRONT_RIGHT -> 32f to .78f
    SpatialChannel.CENTER -> 0f to .8f
    SpatialChannel.LFE -> 0f to .38f
    SpatialChannel.SIDE_LEFT -> -90f to .8f
    SpatialChannel.SIDE_RIGHT -> 90f to .8f
    SpatialChannel.BACK_LEFT -> -148f to .8f
    SpatialChannel.BACK_RIGHT -> 148f to .8f
}

/** The channels of the layout as circles around the listener, each as bright and as large as what sounds in it. */
@Composable
private fun ChannelGraphic(report: SpatialReport, on: Boolean) {
    val levels = remember { FloatArray(8) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(on) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            val source = if (on) SpatialState.levels() else null
            for (k in 0 until 8) { val target = if (source != null) meterLevel(source[k]) else 0f; levels[k] += (target - levels[k]) * .5f }
            frame++
        }
    }
    val layout = if (report.channels == 8) 8 else 6
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 10.sp, color = dim)
    Canvas(Modifier.fillMaxWidth().height(260.dp)) {
        frame.let { }
        val center = Offset(size.width / 2, size.height / 2)
        val radius = minOf(size.width, size.height) / 2 - 22.dp.toPx()
        drawCircle(dim.copy(alpha = .18f), radius, center, style = Stroke(1.dp.toPx()))
        drawCircle(dim.copy(alpha = .10f), radius * .5f, center, style = Stroke(1.dp.toPx()))
        // The listener.
        drawCircle(dim.copy(alpha = .6f), 6.dp.toPx(), center)
        SpatialChannel.entries.take(layout).forEachIndexed { index, channel ->
            val (angle, distance) = place(channel)
            val a = Math.toRadians(angle.toDouble())
            val at = Offset(center.x + (sin(a) * distance * radius).toFloat(), center.y - (cos(a) * distance * radius).toFloat())
            val level = levels[index]
            val base = 11.dp.toPx()
            drawCircle(dim.copy(alpha = .35f), base, at, style = Stroke(1.5.dp.toPx()))
            if (level > .01f) {
                drawCircle(primary.copy(alpha = .25f * level), base + 16.dp.toPx() * level, at)
                drawCircle(primary.copy(alpha = .35f + .65f * level), base * (.5f + .5f * level), at)
            }
            label(measurer, channel.short, at, base, label)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.label(measurer: TextMeasurer, text: String, at: Offset, base: Float, style: TextStyle) {
    val measured = measurer.measure(text, style)
    drawText(measured, topLeft = Offset(at.x - measured.size.width / 2f, at.y + base + 4.dp.toPx()))
}
