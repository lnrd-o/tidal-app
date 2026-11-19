package io.github.lnrdo.mytidal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lnrdo.mytidal.R
import io.github.lnrdo.mytidal.data.Settings
import io.github.lnrdo.mytidal.playback.dj.neural.Accelerator
import io.github.lnrdo.mytidal.playback.dj.neural.GenreModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The switch that puts the genre model (a convolutional network, which graphics processors run well) on the GPU, with what it did last. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun GenreGpuRow(settings: Settings, change: ((Settings) -> Settings) -> Unit) {
    val context = LocalContext.current
    val hardware by produceState<DeviceHardware?>(null) { value = withContext(Dispatchers.Default) { HardwareInfo.read(context.applicationContext) } }
    val model by GenreModel.state.collectAsStateWithLifecycle()
    val has = hardware?.gpu != null && Accelerator.GPU !in model.failed
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.genre_gpu_title), style = MaterialTheme.typography.bodyMedium, color = if (has) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when {
                    Accelerator.GPU in model.failed -> stringResource(R.string.genre_gpu_failed)
                    hardware?.gpu == null -> stringResource(R.string.rhythm_unavailable)
                    model.loaded == Accelerator.GPU -> stringResource(R.string.genre_gpu_running, model.lastMs?.toString() ?: "—", model.lastPatches)
                    else -> stringResource(R.string.genre_gpu_text)
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(settings.djGenreGpu && has, { value -> change { it.copy(djGenreGpu = value) } }, enabled = has)
        }
    }
}
