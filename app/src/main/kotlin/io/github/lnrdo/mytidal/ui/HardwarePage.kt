package io.github.lnrdo.mytidal.ui

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.lnrdo.mytidal.R
import io.github.lnrdo.mytidal.core.DeviceProfile
import io.github.lnrdo.mytidal.core.DeviceTier
import io.github.lnrdo.mytidal.core.NpuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The hardware of this device, one block per part, with what D'JAI makes of it and the settings that it recommends. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun HardwarePage(state: AppState, vm: MainViewModel) {
    val context = LocalContext.current
    val profile by produceState<DeviceProfile?>(null) { value = withContext(Dispatchers.Default) { HardwareInfo.profile(context.applicationContext) } }
    val hardware by produceState<DeviceHardware?>(null) { value = withContext(Dispatchers.Default) { HardwareInfo.read(context.applicationContext) } }
    val p = profile ?: return
    val f = p.facts
    val unknown = stringResource(R.string.hw_unknown)
    val yes = stringResource(R.string.hw_yes)
    val no = stringResource(R.string.hw_no)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionTitle(stringResource(R.string.hw_section_device)) }
        item { SettingRow(OpenTuneIcons.Info, stringResource(R.string.hw_device), "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}") }
        item { SettingRow(OpenTuneIcons.Info, stringResource(R.string.hw_android), stringResource(R.string.hw_android_value, Build.VERSION.RELEASE, f.sdk)) }
        item { SettingRow(OpenTuneIcons.Info, stringResource(R.string.hw_memory), stringResource(R.string.hw_memory_value, "%.1f".format(f.ramMb / 1024.0))) }
        item { SectionTitle(stringResource(R.string.hw_section_processors)) }
        item { SettingRow(OpenTuneIcons.Info, stringResource(R.string.hw_chip), f.chip ?: unknown) }
        item { SettingRow(OpenTuneIcons.Info, stringResource(R.string.hw_cores), stringResource(R.string.hw_cores_value, f.cores, hardware?.clusters ?: "—")) }
        item { SettingRow(OpenTuneIcons.Info, stringResource(R.string.hw_gpu_label), (f.gpu ?: stringResource(R.string.hw_none)) + if (f.vulkan) stringResource(R.string.hw_vulkan) else "") }
        item {
            val text = when (p.npu) {
                NpuStatus.USABLE -> stringResource(R.string.hw_npu_usable)
                NpuStatus.OLDER_GENERATION -> stringResource(R.string.hw_npu_older)
                NpuStatus.OTHER_RUNTIME -> stringResource(R.string.hw_npu_other)
                NpuStatus.NONE -> stringResource(R.string.hw_npu_none)
            }
            SettingRow(OpenTuneIcons.Info, stringResource(R.string.hw_npu_label), (p.npuName ?: stringResource(R.string.hw_none)) + "\n" + text)
        }
        item { SectionTitle(stringResource(R.string.hw_section_audio)) }
        item { SettingRow(OpenTuneIcons.Info, stringResource(R.string.hw_usb), if (f.usbHost) yes else no) }
        item { SettingRow(OpenTuneIcons.Info, stringResource(R.string.hw_low_latency), if (f.lowLatencyAudio) yes else no) }
        item { SectionTitle(stringResource(R.string.hw_section_djai)) }
        item {
            val tier = when (p.tier) { DeviceTier.HIGH -> R.string.hw_tier_high; DeviceTier.MID -> R.string.hw_tier_mid; DeviceTier.LOW -> R.string.hw_tier_low }
            SettingRow(OpenTuneIcons.Djai, stringResource(R.string.hw_tier), stringResource(tier))
        }
        item {
            val r = p.recommendation
            SettingRow(OpenTuneIcons.Djai, stringResource(R.string.hw_recommended), stringResource(R.string.hw_recommended_value,
                if (r.neuralRhythm) stringResource(R.string.hw_rhythm_npu) else stringResource(R.string.hw_rhythm_classic), if (r.genreAi) yes else no, r.workers, r.listenAhead))
        }
        item { SettingRow(OpenTuneIcons.Refresh, stringResource(R.string.hw_reset), stringResource(R.string.hw_reset_text), { vm.settings { it.withRecommendation(p) } }) }
    }
}
