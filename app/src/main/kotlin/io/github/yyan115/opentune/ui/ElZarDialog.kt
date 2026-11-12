package io.github.yyan115.opentune.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yyan115.opentune.R
import io.github.yyan115.opentune.data.Settings
import io.github.yyan115.opentune.playback.dj.DjMix
import kotlinx.coroutines.isActive

/**
 * The panel of the tone module: a switch, a loudness-matched comparison with the original, and the stages in the order the sound meets them:
 * glue (with its flavor and a mid/side mode), console, tube, tape (with its kind), air, soft clip and vinyl (with its condition and speed).
 * The sliders act at once, so the change can be heard while the finger moves.
 */
@Composable
fun ElZarDialog(settings: Settings, onChange: ((Settings) -> Settings) -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.elzar_name)) }, text = {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.elzar_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Toggle(R.string.elzar_on, null, settings.elzar, true) { value -> onChange { it.copy(elzar = value) } }
            Toggle(R.string.elzar_bypass, R.string.elzar_bypass_text, settings.elzarBypass, settings.elzar) { value -> onChange { it.copy(elzarBypass = value) } }

            Chips(R.string.elzar_flavor, flavorNames, flavorHints, settings.elzarFlavor, settings.elzar) { value -> onChange { it.copy(elzarFlavor = value) } }
            Stage(R.string.elzar_glue, R.string.elzar_glue_text, settings.elzarGlue, settings.elzar) { value -> onChange { it.copy(elzarGlue = value) } }
            Toggle(R.string.elzar_midside, R.string.elzar_midside_text, settings.elzarMidSide, settings.elzar) { value -> onChange { it.copy(elzarMidSide = value) } }
            Stage(R.string.elzar_console, R.string.elzar_console_text, settings.elzarConsole, settings.elzar) { value -> onChange { it.copy(elzarConsole = value) } }
            Stage(R.string.elzar_tube, R.string.elzar_tube_text, settings.elzarTube, settings.elzar) { value -> onChange { it.copy(elzarTube = value) } }
            Chips(R.string.elzar_tape_type, tapeNames, tapeHints, settings.elzarTapeType, settings.elzar) { value -> onChange { it.copy(elzarTapeType = value) } }
            Stage(R.string.elzar_tape, R.string.elzar_tape_text, settings.elzarTape, settings.elzar) { value -> onChange { it.copy(elzarTape = value) } }
            Stage(R.string.elzar_air, R.string.elzar_air_text, settings.elzarAir, settings.elzar) { value -> onChange { it.copy(elzarAir = value) } }
            Stage(R.string.elzar_clip, R.string.elzar_clip_text, settings.elzarClip, settings.elzar) { value -> onChange { it.copy(elzarClip = value) } }
            Chips(R.string.elzar_vinyl_condition, vinylNames, vinylHints, settings.elzarVinylCondition, settings.elzar) { value -> onChange { it.copy(elzarVinylCondition = value) } }
            Chips(R.string.elzar_vinyl_speed, speedNames, null, if (settings.elzarVinylFast) 1 else 0, settings.elzar) { value -> onChange { it.copy(elzarVinylFast = value == 1) } }
            Stage(R.string.elzar_vinyl, R.string.elzar_vinyl_text, settings.elzarVinyl, settings.elzar) { value -> onChange { it.copy(elzarVinyl = value) } }
            if (settings.elzar) ReductionMeter()
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) } })
}

@Composable
private fun Toggle(title: Int, hint: Int?, value: Boolean, enabled: Boolean, onValue: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Switch(value, onValue, enabled = enabled)
        }
        if (hint != null) Text(stringResource(hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Stage(title: Int, hint: Int, value: Int, enabled: Boolean, onValue: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("$value", fontFamily = AppFonts.mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value.toFloat(), { onValue(it.toInt()) }, enabled = enabled, valueRange = 0f..100f)
        Text(stringResource(hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val flavorNames = intArrayOf(R.string.flavor_studio, R.string.flavor_opto, R.string.flavor_fet, R.string.flavor_vca, R.string.flavor_varimu, R.string.flavor_diode, R.string.flavor_parallel)
private val flavorHints = intArrayOf(R.string.flavor_studio_text, R.string.flavor_opto_text, R.string.flavor_fet_text, R.string.flavor_vca_text, R.string.flavor_varimu_text, R.string.flavor_diode_text, R.string.flavor_parallel_text)
private val tapeNames = intArrayOf(R.string.tape_studio, R.string.tape_vintage, R.string.tape_cassette, R.string.tape_worn)
private val tapeHints = intArrayOf(R.string.tape_studio_text, R.string.tape_vintage_text, R.string.tape_cassette_text, R.string.tape_worn_text)
private val vinylNames = intArrayOf(R.string.vinyl_new, R.string.vinyl_worn, R.string.vinyl_dusty)
private val vinylHints = intArrayOf(R.string.vinyl_new_text, R.string.vinyl_worn_text, R.string.vinyl_dusty_text)
private val speedNames = intArrayOf(R.string.vinyl_33, R.string.vinyl_45)

/** A choice as square chips, with a line that says how the chosen one sounds. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(title: Int, names: IntArray, hints: IntArray?, selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)) {
        Text(stringResource(title).uppercase(), fontFamily = AppFonts.body, fontSize = 10.sp, letterSpacing = 1.4.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in names.indices) {
                val on = i == selected
                Box(Modifier.border(1.dp, if (on) Color.White else Color.White.copy(alpha = .18f)).clickable(enabled = enabled) { onSelect(i) }.padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Text(stringResource(names[i]), fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (hints != null) Text(stringResource(hints[selected.coerceIn(0, hints.lastIndex)]), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Twelve square cells, one for each decibel of gain reduction. The mixer writes the value, and the canvas reads it on each frame. */
@Composable
private fun ReductionMeter() {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (isActive) withFrameNanos { frame++ } }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(stringResource(R.string.elzar_reduction).uppercase(), fontFamily = AppFonts.body, fontSize = 10.sp, letterSpacing = 1.4.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(16.dp).padding(top = 6.dp)) {
            frame
            val reduction = DjMix.mixer.elzar.reductionDb
            val cells = 12
            val pitch = size.width / cells
            for (i in 0 until cells) {
                val lit = reduction > i + 0.5f
                drawRect(if (lit) Color.White else Color.White.copy(alpha = .1f), Offset(i * pitch + 1f, 0f), Size(pitch - 2f, size.height))
            }
        }
    }
}
