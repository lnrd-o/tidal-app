package io.github.yyan115.opentune.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yyan115.opentune.R
import io.github.yyan115.opentune.playback.dj.DjMix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The processors of the device and what D'JAI takes of them now. The processor does the work. The graphics processor and the neural processor are shown when
 * the device has them, with a switch that stays off: nothing in D'JAI's analysis runs faster on them, and the panel says why instead of pretending.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun HardwarePanel() {
    val context = LocalContext.current
    val hardware by produceState<DeviceHardware?>(null) { value = withContext(Dispatchers.Default) { HardwareInfo.read(context.applicationContext) } }
    var cpu by remember { mutableStateOf<Double?>(null) }
    var reading by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            cpu = withContext(Dispatchers.IO) { HardwareInfo.cpuPercent() } ?: cpu
            reading = (DjMix.ANALYSIS_SLOT..DjMix.SCOUT_SLOT).count { DjMix.streams[it].ring != null }
            delay(1_000)
        }
    }
    val h = hardware ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ProcessorRow(stringResource(R.string.hw_cpu, h.chip ?: stringResource(R.string.hw_unknown), h.cores), h.clusters, active = true, available = true)
        Text(stringResource(R.string.hw_usage, cpu?.let { "%.0f".format(it) } ?: "—", reading), fontFamily = AppFonts.mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        val running = io.github.yyan115.opentune.playback.dj.neural.BeatModel.state.collectAsStateWithLifecycle().value.loaded
        ProcessorRow(stringResource(R.string.hw_gpu, h.gpu ?: stringResource(R.string.hw_none)),
            if (h.gpu != null) stringResource(R.string.hw_gpu_text2, if (h.vulkan) stringResource(R.string.hw_vulkan) else "") else null,
            active = running == io.github.yyan115.opentune.playback.dj.neural.Accelerator.GPU, available = h.gpu != null)
        ProcessorRow(stringResource(R.string.hw_npu, h.npu ?: stringResource(R.string.hw_none)), if (h.npu != null) stringResource(R.string.hw_npu_text2) else null,
            active = running == io.github.yyan115.opentune.playback.dj.neural.Accelerator.NPU, available = h.npu != null)
        val model by io.github.yyan115.opentune.playback.dj.neural.BeatModel.state.collectAsStateWithLifecycle()
        val on = model.loaded
        Text(stringResource(R.string.hw_model_usage, on?.name ?: "—", "%.0f".format(model.busyPercent), model.lastWindowMs?.toString() ?: "—"),
            fontFamily = AppFonts.mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProcessorRow(title: String, hint: String?, active: Boolean, available: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (hint != null) Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The processor is always on; the others are off, and cannot be turned on until D'JAI has work that is faster there.
        Switch(active, {}, enabled = false)
    }
}
