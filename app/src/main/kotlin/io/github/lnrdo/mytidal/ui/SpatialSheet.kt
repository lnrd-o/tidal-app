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
import androidx.compose.ui.graphics.drawscope.translate
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
    var render by remember { mutableStateOf(SpatialState.render) }
    LaunchedEffect(Unit) { while (true) { delay(1500); report = SpatialSupport.report(context); render = SpatialState.render } }
    val selected = SpatialMode.of(state.settings.spatialMode)
    ModalBottomSheet(onClose, shape = SheetShape, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, dragHandle = { SheetHandle() }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.spatial_title), style = AppFonts.title(30, 34))
            Text(statusText(report), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected != null) Text(stringResource(when (render) {
                io.github.lnrdo.mytidal.playback.SpatialRender.SYSTEM -> R.string.spatial_render_system
                io.github.lnrdo.mytidal.playback.SpatialRender.HEADPHONES -> R.string.spatial_render_headphones
                io.github.lnrdo.mytidal.playback.SpatialRender.SPEAKERS -> R.string.spatial_render_speakers
            }), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected == null, { vm.settings { it.copy(spatialMode = "off") } }, { Text(stringResource(R.string.spatial_off)) })
                SpatialMode.entries.forEach { mode ->
                    FilterChip(selected == mode, { vm.settings { it.copy(spatialMode = mode.id) } }, { Text(stringResource(modeName(mode))) })
                }
            }
            Text(stringResource(selected?.let(::modeText) ?: R.string.spatial_off_text), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Who places the channels: the app (always works), or the system's spatializer where it says it can.
            if (report.usable) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.spatial_use_system), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.spatial_use_system_text), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(state.settings.spatialSystem, { on -> vm.settings { it.copy(spatialSystem = on) } })
            }
            // The layout: 5.1 or 7.1. For the system, each when the output spatializes it; the app renders both.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(6 to R.string.spatial_layout_51, 8 to R.string.spatial_layout_71).forEach { (channels, name) ->
                    val can = !state.settings.spatialSystem || (if (channels == 6) report.five else report.seven)
                    FilterChip(state.settings.spatialLayout == channels, { vm.settings { it.copy(spatialLayout = channels) } }, { Text(stringResource(name)) }, enabled = can)
                }
            }
            ChannelGraphic(SpatialState.layout.takeIf { it > 0 } ?: state.settings.spatialLayout, selected != null)
        }
    }
}

@Composable
private fun statusText(report: SpatialReport): String {
    val where = stringResource(when (report.output) { SpatialReport.Output.HEADPHONES -> R.string.spatial_out_headphones; SpatialReport.Output.SPEAKER -> R.string.spatial_out_speaker; else -> R.string.spatial_out_other })
    val dolby = if (report.dolby) stringResource(R.string.spatial_dolby) + " · " else ""
    return dolby + where + " · " + stringResource(when {
        report.usable -> R.string.spatial_ready
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

/** What the graphic shows in each channel: the band of the upmixer's meter, its icon, its colour and its name. The middle band is the voice at the centre. */
private enum class Sound(val band: Int, val colour: Color, val label: Int) {
    BASS(0, Color(0xFF5B8DEF), R.string.stems_bass), VOICE(1, Color(0xFFD6B56A), R.string.stems_vocals),
    INSTRUMENTS(1, Color(0xFF4FC3A1), R.string.spatial_instruments), DRUMS(2, Color(0xFFE5784D), R.string.spatial_drums);

    val icon get() = when (this) { BASS -> OpenTuneIcons.Bass; VOICE -> OpenTuneIcons.Mic; INSTRUMENTS -> OpenTuneIcons.Note; DRUMS -> OpenTuneIcons.Drum }
}

/**
 * The channels of the layout around the listener, seen from above. Each channel is a circle as bright as what sounds in it, and around it the instruments that
 * it carries, each icon as large as its share: the bass, the voice (in the centre), the other instruments and the ambience, and the drums and cymbals.
 */
@Composable
private fun ChannelGraphic(layout: Int, on: Boolean) {
    val levels = remember { FloatArray(8) }
    val bands = remember { FloatArray(8 * 3) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(on) {
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            val source = if (on) SpatialState.levels() else null
            val band = if (on) SpatialState.bands() else null
            for (k in 0 until 8) { val target = if (source != null) meterLevel(source[k]) else 0f; levels[k] += (target - levels[k]) * .5f }
            for (i in 0 until 24) { val target = if (band != null) meterLevel(band[i]) else 0f; bands[i] += (target - bands[i]) * .35f }
            frame++
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val label = TextStyle(fontSize = 10.sp, color = dim)
    val painters = Sound.entries.associateWith { androidx.compose.ui.graphics.vector.rememberVectorPainter(it.icon) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(Modifier.fillMaxWidth().height(300.dp)) {
            frame.let { }
            val center = Offset(size.width / 2, size.height / 2)
            val radius = minOf(size.width, size.height) / 2 - 34.dp.toPx()
            drawCircle(dim.copy(alpha = .18f), radius, center, style = Stroke(1.dp.toPx()))
            drawCircle(dim.copy(alpha = .10f), radius * .5f, center, style = Stroke(1.dp.toPx()))
            // The listener.
            drawCircle(dim.copy(alpha = .6f), 6.dp.toPx(), center)
            SpatialChannel.entries.take(layout).forEachIndexed { index, channel ->
                val (angle, distance) = place(channel)
                val a = Math.toRadians(angle.toDouble())
                val at = Offset(center.x + (sin(a) * distance * radius).toFloat(), center.y - (cos(a) * distance * radius).toFloat())
                val level = levels[index]
                val base = 9.dp.toPx()
                drawCircle(dim.copy(alpha = .35f), base, at, style = Stroke(1.5.dp.toPx()))
                if (level > .01f) {
                    drawCircle(primary.copy(alpha = .18f * level), base + 12.dp.toPx() * level, at)
                    drawCircle(primary.copy(alpha = .35f + .65f * level), base * (.5f + .5f * level), at)
                }
                label(measurer, channel.short, at, base, label)
                // The instruments of this channel, on a small arc on the side away from the listener, each as large as its band.
                val sounds = listOf(Sound.BASS, if (channel == SpatialChannel.CENTER) Sound.VOICE else Sound.INSTRUMENTS, Sound.DRUMS)
                    .filter { channel != SpatialChannel.LFE || it == Sound.BASS }
                sounds.forEachIndexed { i, sound ->
                    val share = bands[index * 3 + sound.band]
                    if (share < .25f) return@forEachIndexed
                    val spread = (i - (sounds.size - 1) / 2f) * 34.0
                    val b = Math.toRadians(angle + spread)
                    val reach = base + 20.dp.toPx()
                    val icon = (10.dp.toPx() + 12.dp.toPx() * share)
                    val c = Offset(at.x + (sin(b) * reach).toFloat(), at.y - (cos(b) * reach).toFloat())
                    translate(c.x - icon / 2, c.y - icon / 2) {
                        with(painters.getValue(sound)) { draw(androidx.compose.ui.geometry.Size(icon, icon), alpha = (.35f + .65f * share).coerceAtMost(1f), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(sound.colour)) }
                    }
                }
            }
        }
        // The legend.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Sound.entries.forEach { sound ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(sound.icon, null, Modifier.size(14.dp), tint = sound.colour)
                    Text(stringResource(sound.label), style = MaterialTheme.typography.labelSmall, color = dim)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.label(measurer: TextMeasurer, text: String, at: Offset, base: Float, style: TextStyle) {
    val measured = measurer.measure(text, style)
    drawText(measured, topLeft = Offset(at.x - measured.size.width / 2f, at.y + base + 4.dp.toPx()))
}
