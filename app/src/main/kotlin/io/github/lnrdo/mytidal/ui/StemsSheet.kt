@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.lnrdo.mytidal.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.lnrdo.mytidal.R
import io.github.lnrdo.mytidal.core.Track
import io.github.lnrdo.mytidal.core.formatDuration
import io.github.lnrdo.mytidal.core.stems.Stem
import io.github.lnrdo.mytidal.stems.StemModel
import io.github.lnrdo.mytidal.stems.StemSwitch
import io.github.lnrdo.mytidal.stems.StemProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val StemIcons = mapOf(Stem.VOCALS to OpenTuneIcons.Mic, Stem.DRUMS to OpenTuneIcons.Drum, Stem.BASS to OpenTuneIcons.Bass, Stem.OTHER to OpenTuneIcons.Note)
private val StemColours = mapOf(Stem.VOCALS to Color(0xFFD6B56A), Stem.DRUMS to Color(0xFFE5784D), Stem.BASS to Color(0xFF5B8DEF), Stem.OTHER to Color(0xFF4FC3A1))

private val StemNames = mapOf(Stem.VOCALS to R.string.stems_vocals, Stem.DRUMS to R.string.stems_drums, Stem.BASS to R.string.stems_bass, Stem.OTHER to R.string.stems_other)

/**
 * The stems of a song: separate it (with Spleeter, on the NPU, or Open-Unmix, on the CPU), and then play it with a fader, a mute and a solo for each of the
 * four stems. While the mixer plays, the app's own player is paused; when the sheet closes the song goes on in the app's player from where the mixer was.
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun StemsSheet(track: Track, state: AppState, vm: MainViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val manager = vm.app.stems
    val progress by manager.state.collectAsStateWithLifecycle()
    val job = progress[track.id]
    var version by remember { mutableIntStateOf(0) }
    val ready = remember(job, version) { manager.store.has(track.id) }
    // One model, chosen for the device: the best that is installed and that it runs well.
    val model = remember(version, state.settings.stemsModel) { StemModel.chosen(context, state.settings.stemsModel) }
    val auto = state.settings.stemsModel == "auto" || model?.id != state.settings.stemsModel
    var importing by remember { mutableStateOf(StemModel.SPLEETER) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val target = importing.path(context).also { it.parentFile?.mkdirs() }
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            }
            version++
        }
    }
    val power = state.settings.stemsPower

    ModalBottomSheet(onClose, shape = SheetShape, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, dragHandle = { SheetHandle() }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text(stringResource(R.string.stems_title), style = AppFonts.title(30, 34))
                Text(listOf(track.title, track.artist).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            when {
                model == null -> {
                    Text(stringResource(R.string.stems_no_model), style = MaterialTheme.typography.bodyMedium)
                    for (m in StemModel.entries) OutlinedButton({ importing = m; picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.stems_install, modelName(m))) }
                }
                job != null && job.phase != StemProgress.Phase.DONE && job.phase != StemProgress.Phase.FAILED -> Working(job) { manager.cancel(track.id) }
                job?.phase == StemProgress.Phase.FAILED && !ready -> {
                    Text(stringResource(R.string.stems_failed), color = MaterialTheme.colorScheme.error)
                    SeparateControls(model, auto) { manager.separate(track, model, state.settings.stemsCacheMb, power, state.settings.stemsNpu, state.settings.stemsFast) }
                }
                ready -> Mixer(track, state, vm, manager.store.model(track.id), job, onAgain = { manager.delete(track.id); version++ })
                else -> {
                    Text(stringResource(R.string.stems_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SeparateControls(model, auto) { manager.separate(track, model, state.settings.stemsCacheMb, power, state.settings.stemsNpu, state.settings.stemsFast) }
                }
            }
        }
    }
}

@Composable
internal fun modelName(m: StemModel) = stringResource(when (m) { StemModel.SPLEETER -> R.string.stems_model_spleeter; StemModel.OPEN_UNMIX -> R.string.stems_model_umx; StemModel.BOTH -> R.string.stems_model_both; StemModel.SCNET -> R.string.stems_model_scnet })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeparateControls(model: StemModel, auto: Boolean, onSeparate: () -> Unit) {
    Text(if (auto) stringResource(R.string.stems_chosen, modelName(model)) else modelName(model), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(when (model) { StemModel.SPLEETER -> R.string.stems_spleeter_text; StemModel.OPEN_UNMIX -> R.string.stems_umx_text; StemModel.BOTH -> R.string.stems_both_text; StemModel.SCNET -> R.string.stems_scnet_text }),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onSeparate, Modifier.fillMaxWidth()) { Text(stringResource(R.string.stems_separate)) }
}

@Composable
private fun Working(job: StemProgress, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(when (job.phase) { StemProgress.Phase.WAITING -> R.string.stems_waiting; StemProgress.Phase.PREPARING -> R.string.stems_preparing; StemProgress.Phase.READING -> R.string.stems_reading; else -> R.string.stems_separating },
            job.ranOn?.name ?: ""), style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { job.progress }, Modifier.fillMaxWidth())
        TextButton(onCancel) { Text(stringResource(R.string.action_cancel)) }
    }
}

/** Four faders with a mute and a solo each, and a small transport. */
/**
 * The mix of a song with stems. It plays through the app's own player: the switch turns the stems on for every song that has them, the faders, mutes and
 * solo are heard at once and kept, and Reset puts them back to 100%.
 */
@Composable
private fun Mixer(track: Track, state: AppState, vm: MainViewModel, model: String?, job: StemProgress?, onAgain: () -> Unit) {
    val settings = state.settings
    val (levels, mutedBits, soloStem) = remember(settings.stemsMix) { StemSwitch.decode(settings.stemsMix) }
    val gains = remember(settings.stemsMix) { mutableStateListOf(*levels.toTypedArray()) }
    fun keep(g: List<Float> = gains, muted: Int = mutedBits, solo: Int = soloStem) {
        StemSwitch.set(g.toFloatArray(), muted, solo)
        vm.settings { it.copy(stemsMix = StemSwitch.encode(g.toFloatArray(), muted, solo)) }
    }
    val playingThis = state.playback.track?.id == track.id
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.stems_switch), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.stems_switch_text), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(settings.stemsOn, { on -> StemSwitch.enabled = on; vm.settings { it.copy(stemsOn = on) } })
        }
        if (!playingThis) OutlinedButton({ vm.play(listOf(track)) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.stems_play_song)) }
        for (stem in Stem.entries) {
            val i = stem.ordinal
            // The stem is greyed while the stems are off, while it is muted, or while another one is on solo, so what is heard can be read at a glance.
            val muted = mutedBits and (1 shl i) != 0
            val heard = settings.stemsOn && (if (soloStem >= 0) soloStem == i else !muted)
            Row(Modifier.fillMaxWidth().alpha(if (heard) 1f else .45f), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(StemColours.getValue(stem).copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                    Icon(StemIcons.getValue(stem), null, Modifier.size(24.dp), tint = StemColours.getValue(stem))
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(StemNames.getValue(stem)), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("%d%%".format((gains[i] * 100).toInt()), fontFamily = AppFonts.mono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Heard while it is dragged; kept when the finger lifts.
                    Slider(gains[i], { gains[i] = it; StemSwitch.set(gains.toFloatArray(), mutedBits, soloStem) }, Modifier.height(28.dp), valueRange = 0f..1.5f,
                        onValueChangeFinished = { keep() }, colors = SliderDefaults.colors(thumbColor = StemColours.getValue(stem), activeTrackColor = StemColours.getValue(stem)))
                }
                ToggleChip("M", muted) { keep(muted = mutedBits xor (1 shl i)) }
                Spacer(Modifier.width(6.dp))
                ToggleChip("S", soloStem == i) { keep(solo = if (soloStem == i) -1 else i) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val detail = listOfNotNull(model?.let { modelName(StemModel.entries.firstOrNull { m -> m.id == it } ?: StemModel.SPLEETER) },
                job?.ranOn?.name, job?.takeIf { it.seconds > 0 }?.let { stringResource(R.string.stems_took, it.seconds.toInt()) }).joinToString(" · ")
            Text(detail, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ keep(listOf(1f, 1f, 1f, 1f), 0, -1) }) { Text(stringResource(R.string.stems_reset)) }
            TextButton(onAgain) { Text(stringResource(R.string.stems_again)) }
        }
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
    }
}

