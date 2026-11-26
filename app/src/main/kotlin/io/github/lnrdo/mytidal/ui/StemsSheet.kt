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
import io.github.lnrdo.mytidal.stems.StemPlayer
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
    val model = remember(version) { StemModel.best(context) }
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
                    SeparateControls(model, power, { p -> vm.settings { s -> s.copy(stemsPower = p) } }) { manager.separate(track, model, state.settings.stemsCacheMb, power) }
                }
                ready -> Mixer(track, state, vm, manager.store.model(track.id), job, onAgain = { manager.delete(track.id); version++ })
                else -> {
                    Text(stringResource(R.string.stems_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SeparateControls(model, power, { p -> vm.settings { s -> s.copy(stemsPower = p) } }) { manager.separate(track, model, state.settings.stemsCacheMb, power) }
                }
            }
        }
    }
}

@Composable
private fun modelName(m: StemModel) = stringResource(when (m) { StemModel.SPLEETER -> R.string.stems_model_spleeter; StemModel.OPEN_UNMIX -> R.string.stems_model_umx; StemModel.BOTH -> R.string.stems_model_both; StemModel.SCNET -> R.string.stems_model_scnet })

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeparateControls(model: StemModel, power: Int, onPower: (Int) -> Unit, onSeparate: () -> Unit) {
    Text(stringResource(R.string.stems_chosen, modelName(model)), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(when (model) { StemModel.SPLEETER -> R.string.stems_spleeter_text; StemModel.OPEN_UNMIX -> R.string.stems_umx_text; StemModel.BOTH -> R.string.stems_both_text; StemModel.SCNET -> R.string.stems_scnet_text }),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // How steep the cut between the stems is: steeper lets less of one stem into another, and sounds a little more grainy.
    if (model != StemModel.SCNET) Text(stringResource(R.string.stems_sharpness), style = MaterialTheme.typography.bodyMedium)
    if (model != StemModel.SCNET) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf(2 to R.string.stems_sharp_standard, 3 to R.string.stems_sharp_sharper, 4 to R.string.stems_sharp_sharpest).forEachIndexed { i, (p, name) ->
            SegmentedButton(p == power, { onPower(p) }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(stringResource(name), fontSize = 12.sp, maxLines = 1) }
        }
    }
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
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun Mixer(track: Track, state: AppState, vm: MainViewModel, model: String?, job: StemProgress?, onAgain: () -> Unit) {
    val context = LocalContext.current
    val manager = vm.app.stems
    val player = remember(track.id) { runCatching { StemPlayer(manager.store, track.id) }.getOrNull() }
    val wasPlaying = remember { state.playback.playing }
    val startAt = remember { 0L }.let { LocalPlaybackPosition.current.value }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(startAt) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    val gains = remember { mutableStateListOf(1f, 1f, 1f, 1f) }
    val muted = remember { mutableStateListOf(false, false, false, false) }
    var solo by remember { mutableIntStateOf(-1) }
    if (player == null) { Text(stringResource(R.string.stems_failed), color = MaterialTheme.colorScheme.error); return }
    DisposableEffect(player) {
        // The app's player gives way to the mixer, and takes the song back, from where the mixer is, when the sheet closes.
        if (wasPlaying) vm.togglePlay()
        player.seekTo(startAt)
        player.play(); playing = true
        onDispose {
            val at = player.positionMs
            player.release()
            vm.seek(at)
            if (wasPlaying) vm.togglePlay()
        }
    }
    LaunchedEffect(player) { while (true) { position = player.positionMs; playing = player.isPlaying; delay(200) } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (stem in Stem.entries) {
            val i = stem.ordinal
            // The stem is greyed while it is muted or another one is on solo, so what is heard can be read at a glance.
            val heard = if (solo >= 0) solo == i else !muted[i]
            Row(Modifier.fillMaxWidth().alpha(if (heard) 1f else .45f), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(StemColours.getValue(stem).copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                    Icon(StemIcons.getValue(stem), null, Modifier.size(24.dp), tint = StemColours.getValue(stem))
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(StemNames.getValue(stem)), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text("%d%%".format((gains[i] * 100).toInt()), fontFamily = AppFonts.mono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Slider(gains[i], { gains[i] = it; player.setGain(stem, it) }, Modifier.height(28.dp), valueRange = 0f..1.5f,
                        colors = SliderDefaults.colors(thumbColor = StemColours.getValue(stem), activeTrackColor = StemColours.getValue(stem)))
                }
                ToggleChip("M", muted[i]) { muted[i] = !muted[i]; player.setMuted(stem, muted[i]) }
                Spacer(Modifier.width(6.dp))
                ToggleChip("S", solo == i) { solo = if (solo == i) -1 else i; player.setSolo(if (solo == -1) null else stem) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ if (player.isPlaying) player.pause() else player.play() }) {
                Icon(if (playing) OpenTuneIcons.PauseSolid else OpenTuneIcons.PlaySolid, stringResource(if (playing) R.string.player_pause else R.string.player_play))
            }
            Text(formatDuration(seeking?.toLong() ?: position), fontFamily = AppFonts.mono, fontSize = 12.sp)
            Slider(seeking ?: position.toFloat(), { seeking = it }, Modifier.weight(1f).padding(horizontal = 8.dp), valueRange = 0f..player.durationMs.coerceAtLeast(1).toFloat(),
                onValueChangeFinished = { seeking?.let { player.seekTo(it.toLong()) }; seeking = null })
            Text(formatDuration(player.durationMs), fontFamily = AppFonts.mono, fontSize = 12.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val detail = listOfNotNull(model?.let { modelName(StemModel.entries.firstOrNull { m -> m.id == it } ?: StemModel.SPLEETER) },
                job?.ranOn?.name, job?.takeIf { it.seconds > 0 }?.let { stringResource(R.string.stems_took, it.seconds.toInt()) }).joinToString(" · ")
            Text(detail, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

