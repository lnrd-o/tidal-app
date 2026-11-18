package io.github.yyan115.opentune.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yyan115.opentune.R
import io.github.yyan115.opentune.data.Settings
import io.github.yyan115.opentune.playback.dj.neural.Accelerator
import io.github.yyan115.opentune.playback.dj.neural.BeatModel
import io.github.yyan115.opentune.playback.dj.neural.RhythmStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The rhythm model on the D'JAI page: on or off, where it runs (the NPU and GPU switches are real and turn on only where the phone has them), how busy it
 * keeps the accelerator, and the listener's answers that train it, with export and import as a JSON file.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun NeuralPanel(settings: Settings, change: ((Settings) -> Settings) -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    RhythmStore.init(context.applicationContext)
    val model by BeatModel.state.collectAsStateWithLifecycle()
    val stats by RhythmStore.stats.collectAsStateWithLifecycle()
    val songs by RhythmStore.songs.collectAsStateWithLifecycle()
    var probing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val chosen = Accelerator.entries.firstOrNull { it.name == settings.djAccelerator } ?: Accelerator.AUTO
    // Which accelerators the phone has is learned once, by opening the model on each.
    LaunchedEffect(settings.djNeural) {
        if (settings.djNeural && model.available.isEmpty() && !probing) {
            probing = true
            withContext(Dispatchers.Default) { runCatching { BeatModel.probe(context.applicationContext, chosen) } }
            probing = false
        }
    }
    LaunchedEffect(Unit) { while (true) { BeatModel.refreshBusy(); delay(1_000) } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(RhythmStore.export().toByteArray()) } }
                .onSuccess { message = resources.getString(R.string.rhythm_exported) }.onFailure { message = resources.getString(R.string.rhythm_export_failed) }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { RhythmStore.import(it.readBytes().decodeToString()) } ?: 0 }
                .onSuccess { message = resources.getString(R.string.rhythm_imported, it) }.onFailure { message = resources.getString(R.string.rhythm_import_failed) }
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.rhythm_title), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.rhythm_text), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(settings.djNeural, { value -> change { it.copy(djNeural = value) } })
        }
        if (settings.djNeural) {
            // The accelerators, as real switches: one can be on at a time, and only those that opened on this phone can be chosen.
            for (on in listOf(Accelerator.NPU, Accelerator.GPU)) {
                val has = on in model.available
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(if (on == Accelerator.NPU) R.string.rhythm_use_npu else R.string.rhythm_use_gpu), style = MaterialTheme.typography.bodyMedium,
                            color = if (has) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(when { probing -> stringResource(R.string.rhythm_checking); has -> stringResource(R.string.rhythm_available); else -> stringResource(R.string.rhythm_unavailable) },
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(chosen == on || (chosen == Accelerator.AUTO && model.loaded == on), { value ->
                        change { it.copy(djAccelerator = if (value) on.name else Accelerator.CPU.name) }
                        scope.launch(Dispatchers.Default) { runCatching { BeatModel.open(context.applicationContext, if (value) on else Accelerator.CPU) } }
                    }, enabled = has)
                }
            }
            val running = model.loaded?.name ?: "—"
            Text(stringResource(R.string.rhythm_status, running, "%.0f".format(model.busyPercent),
                model.lastSongMs?.let { "%.1f".format(it / 1000.0) } ?: "—", model.lastSongSeconds?.let { "%d:%02d".format(it.toInt() / 60, it.toInt() % 60) } ?: "—"),
                fontFamily = AppFonts.mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.rhythm_learned, songs.size, stats.first, "%.0f".format(stats.second * 100)), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action(stringResource(R.string.rhythm_export)) { exporter.launch("opentune-rhythm.json") }
                Action(stringResource(R.string.rhythm_import)) { importer.launch(arrayOf("application/json", "*/*")) }
                Action(stringResource(R.string.rhythm_forget)) { RhythmStore.forgetAnswers(); message = resources.getString(R.string.rhythm_forgotten) }
            }
            message?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Box(Modifier.border(1.dp, Color.White.copy(alpha = .35f)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp)) { Text(label, fontSize = 12.sp) }
}
