package io.github.yyan115.opentune.ui

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import io.github.yyan115.opentune.R
import io.github.yyan115.opentune.core.Spectrum
import io.github.yyan115.opentune.core.Track
import io.github.yyan115.opentune.playback.AudioSpectrum
import io.github.yyan115.opentune.playback.SignalPath
import io.github.yyan115.opentune.playback.dj.DjMix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** The parts of a stream label such as "FLAC · 24-bit · 96 kHz". */
data class SourceReadout(val codec: String, val bits: String?, val sampleRate: String?, val bitrate: String? = null) {
    /** FLAC, ALAC and plain PCM carry every sample of the master. */
    val lossless: Boolean get() = codec.uppercase().let { it.startsWith("FLAC") || it.startsWith("ALAC") || it.startsWith("WAV") || it.startsWith("PCM") }

    companion object {
        fun from(label: String): SourceReadout {
            val raw = label.substringBefore(" · ").trim()
            val codec = when { raw.startsWith("MP4A", true) -> "AAC"; raw.isBlank() -> ""; else -> raw.uppercase() }
            val bits = Regex("(\\d+)-bit").find(label)?.groupValues?.get(1)
            val rate = Regex("([\\d.]+) kHz").find(label)?.groupValues?.get(1)
            val bitrate = Regex("(\\d+) kbps").find(label)?.groupValues?.get(1)
            return SourceReadout(codec, bits, rate, bitrate)
        }
    }
}

@Composable fun SourceReadout.bitsText(): String? = bits?.let { stringResource(R.string.unit_bits, it) }
@Composable fun SourceReadout.rateText(): String? = sampleRate?.let { stringResource(R.string.unit_khz, it) }
@Composable fun SourceReadout.bitrateText(): String? = bitrate?.let { stringResource(R.string.unit_kbps, it) }

/** TIDAL's names for its quality levels: MAX for hi-res lossless, HiFi for CD-quality lossless, and the codec otherwise. */
enum class QualityTier { MAX, HIFI, LOSSY, UNKNOWN }

/** The tier of a stream from its label, with the decoded format to fill in what the label leaves out. */
fun qualityTier(label: String, decoded: SignalPath.Decoded? = null): QualityTier {
    val source = SourceReadout.from(label)
    if (source.codec.isBlank()) return QualityTier.UNKNOWN
    if (!source.lossless) return QualityTier.LOSSY
    val bits = source.bits?.toIntOrNull() ?: 16
    val rate = source.sampleRate?.toDoubleOrNull() ?: decoded?.sampleRate?.div(1000.0) ?: 44.1
    return if (bits >= 24 || rate > 48.0) QualityTier.MAX else QualityTier.HIFI
}

/** The small rectangular span that names the quality of the stream. A tap opens the signal path. */
@Composable
fun QualityBadge(label: String, modifier: Modifier = Modifier, color: Color? = null, onClick: () -> Unit) {
    val source = SourceReadout.from(label)
    val tier = qualityTier(label)
    // Gold for MAX, cyan for HiFi, white for everything else.
    val tint = color ?: when (tier) { QualityTier.MAX -> Color(0xFFE3B341); QualityTier.HIFI -> Color(0xFF22D3EE); else -> Color.White }
    val name = when (tier) {
        QualityTier.MAX -> stringResource(R.string.badge_max)
        QualityTier.HIFI -> stringResource(R.string.badge_hifi)
        QualityTier.LOSSY -> source.codec
        QualityTier.UNKNOWN -> stringResource(R.string.path_badge_unknown)
    }
    val detail = listOfNotNull(source.bits, source.sampleRate).joinToString("/").ifBlank { null }
    Row(modifier.testTag("quality-badge").border(1.dp, tint.copy(alpha = .7f), RectangleShape).background(tint.copy(alpha = .12f))
        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.path_open), onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontFamily = AppFonts.body, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = tint)
        if (detail != null) {
            Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(9.dp).background(tint.copy(alpha = .4f)))
            Text(detail, fontFamily = AppFonts.mono, fontSize = 9.sp, lineHeight = 12.sp, color = tint.copy(alpha = .85f))
        }
    }
}

/** How faithfully one stage passes the music on. The worst stage decides the verdict of the whole path. */
private enum class Fidelity { HIRES, LOSSLESS, ENHANCED, LOSSY }

private class Stage(val icon: ImageVector?, val tag: String?, val title: String, val detail: String, val fidelity: Fidelity, val explanation: String)

/** The output that Android sends media to now. */
private class Output(val kind: Kind, val name: String?, val mixerRate: Int?) { enum class Kind { SPEAKER, WIRED, USB, BLUETOOTH, OTHER } }

private fun Int.kind(): Output.Kind = when (this) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> Output.Kind.SPEAKER
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG -> Output.Kind.WIRED
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> Output.Kind.USB
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 26, 27, 30 -> Output.Kind.BLUETOOTH // BLE headset, speaker, broadcast
    else -> Output.Kind.OTHER
}

private fun readOutput(manager: AudioManager): Output {
    val device = if (Build.VERSION.SDK_INT >= 33) runCatching {
        manager.getAudioDevicesForAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()).firstOrNull()
    }.getOrNull() else null
    val chosen = device ?: manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).minByOrNull {
        when (it.type.kind()) { Output.Kind.USB -> 0; Output.Kind.WIRED -> 1; Output.Kind.BLUETOOTH -> 2; Output.Kind.OTHER -> 3; Output.Kind.SPEAKER -> 4 }
    }
    val kind = chosen?.type?.kind() ?: Output.Kind.SPEAKER
    val name = chosen?.productName?.toString()?.takeIf { it.isNotBlank() && kind != Output.Kind.SPEAKER && it != Build.MODEL }
    return Output(kind, name, manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull())
}

@Composable
private fun rememberOutput(): State<Output> {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val output = remember { mutableStateOf(readOutput(manager)) }
    DisposableEffect(manager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { output.value = readOutput(manager) }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { output.value = readOutput(manager) }
        }
        manager.registerAudioDeviceCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { manager.unregisterAudioDeviceCallback(callback) }
    }
    return output
}

/** The decoded format, the bit-perfect device and the normalization gain, read twice a second while the panel is open. */
private data class Live(val decoded: SignalPath.Decoded?, val bitPerfect: String?, val normalizationDb: Float, val routing: Boolean = false, val mixing: Boolean = false)

@Composable
private fun rememberLive(): State<Live> = produceState(Live(SignalPath.decoded, SignalPath.bitPerfectDevice, SignalPath.normalizationDb)) {
    while (isActive) { value = Live(SignalPath.decoded, SignalPath.bitPerfectDevice, SignalPath.normalizationDb, DjMix.routing, DjMix.mixer.mixing); delay(500) }
}

private fun khz(hz: Int): String = (hz / 1000.0).let { if (abs(it - it.roundToInt()) < .01) it.roundToInt().toString() else "%.1f".format(it) }

/**
 * The signal path of the current track, from the stream to the output, with a live spectrum of what plays.
 * Every value is read from the player and the device: the label of the stream, the format the decoder hands on, and the route that Android chose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun SignalPathSheet(label: String, track: Track?, playing: Boolean, onSpectrum: () -> Unit = {}, elzar: Triple<Int, Int, Int>? = null, onClose: () -> Unit) {
    val live by rememberLive()
    val output by rememberOutput()
    val source = SourceReadout.from(label)
    val decoded = live.decoded
    val tier = qualityTier(label, decoded)

    val stages = buildList {
        // Source: what TIDAL or the download delivers.
        val sourceBits = source.bits?.toIntOrNull()
        val sourceRate = source.sampleRate ?: decoded?.let { khz(it.sampleRate) }
        val origin = stringResource(if (track?.onDevice == true) R.string.path_origin_download else R.string.path_origin_tidal)
        val sourceParts = listOfNotNull(origin, source.codec.ifBlank { null }, sourceRate?.let { stringResource(R.string.unit_khz, it) },
            sourceBits?.let { stringResource(R.string.path_bits, it) }, source.bitrate?.let { stringResource(R.string.unit_kbps, it) },
            decoded?.let { stringResource(R.string.path_channels, it.channels) })
        add(Stage(null, source.codec.ifBlank { "?" }.take(4), stringResource(R.string.path_source), sourceParts.joinToString(" "),
            when (tier) { QualityTier.MAX -> Fidelity.HIRES; QualityTier.HIFI -> Fidelity.LOSSLESS; else -> Fidelity.LOSSY },
            stringResource(if (source.lossless) R.string.path_source_lossless else R.string.path_source_lossy)))

        // Decoder: the PCM that the player hands on.
        if (decoded != null) {
            val truncated = sourceBits != null && !decoded.float && decoded.bits < sourceBits
            val detail = if (decoded.float) stringResource(R.string.path_decoded_float, khz(decoded.sampleRate))
                else stringResource(R.string.path_decoded_pcm, decoded.bits, khz(decoded.sampleRate))
            add(Stage(OpenTuneIcons.GraphicEq, null, stringResource(R.string.path_decoder), detail,
                if (truncated) Fidelity.ENHANCED else if (tier == QualityTier.MAX) Fidelity.HIRES else if (source.lossless) Fidelity.LOSSLESS else Fidelity.LOSSY,
                stringResource(if (truncated) R.string.path_decoder_truncated else R.string.path_decoder_exact)))
        }

        // D'JAI plays through its own mixer, which resamples to 48 kHz and, while two tracks blend, changes their EQ.
        if (live.routing) add(Stage(OpenTuneIcons.Tune, null, stringResource(R.string.path_mixer),
            stringResource(if (live.mixing) R.string.path_mixer_mixing else R.string.path_mixer_idle, "48"), Fidelity.ENHANCED, stringResource(R.string.path_mixer_explain)))

        // Tone Lab colours the sound on purpose.
        if (live.routing && elzar != null) add(Stage(OpenTuneIcons.Tune, null, stringResource(R.string.path_elzar),
            stringResource(R.string.path_elzar_detail, elzar.first, elzar.second, elzar.third), Fidelity.ENHANCED, stringResource(R.string.path_elzar_explain)))

        // Volume normalization changes the samples, with high precision.
        if (live.normalizationDb != 0f) add(Stage(OpenTuneIcons.VolumeDown, null, stringResource(R.string.path_normalization),
            stringResource(R.string.path_normalization_db, "%.1f".format(live.normalizationDb)), Fidelity.ENHANCED, stringResource(R.string.path_normalization_explain)))

        // Output: the device and what Android does on the way to it.
        val outputTitle = output.name ?: stringResource(when (output.kind) {
            Output.Kind.SPEAKER -> R.string.path_output_speaker; Output.Kind.WIRED -> R.string.path_output_wired
            Output.Kind.USB -> R.string.path_output_usb; Output.Kind.BLUETOOTH -> R.string.path_output_bluetooth; Output.Kind.OTHER -> R.string.path_output_other
        })
        val icon = when (output.kind) { Output.Kind.BLUETOOTH -> OpenTuneIcons.Bluetooth; Output.Kind.USB -> OpenTuneIcons.Usb; Output.Kind.WIRED -> OpenTuneIcons.Headphones; else -> OpenTuneIcons.Speaker }
        val mixer = output.mixerRate
        val resampled = live.bitPerfect == null && mixer != null && decoded != null && mixer != decoded.sampleRate
        when {
            live.bitPerfect != null -> add(Stage(icon, null, outputTitle, stringResource(R.string.path_output_bitperfect), if (tier == QualityTier.MAX) Fidelity.HIRES else Fidelity.LOSSLESS, stringResource(R.string.path_output_bitperfect_explain)))
            output.kind == Output.Kind.BLUETOOTH -> add(Stage(icon, null, outputTitle, stringResource(R.string.path_output_bluetooth_detail), Fidelity.LOSSY, stringResource(R.string.path_output_bluetooth_explain)))
            resampled -> add(Stage(icon, null, outputTitle, stringResource(R.string.path_output_resampled, khz(mixer!!)), Fidelity.ENHANCED, stringResource(R.string.path_output_resampled_explain)))
            else -> add(Stage(icon, null, outputTitle, mixer?.let { stringResource(R.string.path_output_mixer, khz(it)) } ?: stringResource(R.string.path_output_android), Fidelity.LOSSLESS, stringResource(R.string.path_output_mixer_explain)))
        }
    }
    val worst = stages.maxOf { it.fidelity }
    val verdict = stringResource(when {
        worst == Fidelity.LOSSY -> R.string.path_verdict_lossy
        worst == Fidelity.ENHANCED -> R.string.path_verdict_enhanced
        stages.first().fidelity == Fidelity.HIRES -> R.string.path_verdict_hires
        else -> R.string.path_verdict_lossless
    })

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), shape = RectangleShape,
        containerColor = Color(0xFF161616), contentColor = Color.White, scrimColor = Color.Black.copy(alpha = .55f),
        dragHandle = { Box(Modifier.padding(vertical = 10.dp).size(32.dp, 3.dp).background(Color.White.copy(alpha = .3f))) }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).testTag("signal-path")) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.path_title), fontFamily = AppFonts.body, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(" $verdict", fontFamily = AppFonts.body, fontSize = 22.sp, fontWeight = FontWeight.Normal, color = Color.White.copy(alpha = .85f))
                Spacer(Modifier.weight(1f))
                FidelityMark(worst, Modifier.padding(bottom = 7.dp))
            }
            Text(stringResource(R.string.path_hint), Modifier.padding(top = 4.dp, bottom = 18.dp), fontFamily = AppFonts.body, fontSize = 12.sp, color = Color.White.copy(alpha = .55f))
            stages.forEachIndexed { index, stage -> StageRow(stage, first = index == 0, last = index == stages.lastIndex) }
            Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.path_spectrum).uppercase(), Modifier.weight(1f), fontFamily = AppFonts.body, fontSize = 10.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = .55f))
                Text(stringResource(R.string.path_fullscreen).uppercase(), Modifier.border(1.dp, Color.White.copy(alpha = .35f), RectangleShape).clickable(role = Role.Button) { onSpectrum() }.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontFamily = AppFonts.body, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium, color = Color.White)
            }
            SpectrumAnalyzer(playing, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FidelityMark(fidelity: Fidelity, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    val color = when (fidelity) { Fidelity.HIRES, Fidelity.LOSSLESS -> Color.White; Fidelity.ENHANCED -> Color(0xFF9E9E9E); Fidelity.LOSSY -> Color(0xFFE0A040) }
    Box(modifier.size(size).then(if (fidelity == Fidelity.LOSSLESS) Modifier.border(1.dp, color, RectangleShape) else Modifier.background(color)))
}

/** One stage: a square mark joined to its neighbours by a line, the name, the measured detail, and an explanation that a tap opens. */
@Composable
private fun StageRow(stage: Stage, first: Boolean, last: Boolean) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable(role = Role.Button) { open = !open }) {
        Box(Modifier.width(40.dp).fillMaxHeight()) {
            Box(Modifier.align(Alignment.TopCenter).width(1.dp).height(8.dp).background(if (first) Color.Transparent else Color.White.copy(alpha = .4f)))
            Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp).width(1.dp).fillMaxHeight().background(if (last) Color.Transparent else Color.White.copy(alpha = .4f)))
            Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(36.dp).background(Color(0xFF161616)).border(1.dp, Color.White, RectangleShape), contentAlignment = Alignment.Center) {
                if (stage.icon != null) Icon(stage.icon, null, Modifier.size(18.dp), tint = Color.White)
                else Text(stage.tag.orEmpty(), fontFamily = AppFonts.body, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .4.sp, maxLines = 1)
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, top = 6.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stage.title, Modifier.weight(1f, fill = false), fontFamily = AppFonts.body, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FidelityMark(stage.fidelity, Modifier.padding(start = 8.dp), 6.dp)
            }
            Text(stage.detail, Modifier.padding(top = 2.dp), fontFamily = AppFonts.body, fontSize = 14.sp, color = Color.White.copy(alpha = .72f))
            AnimatedVisibility(open, enter = fadeIn(tween(160)) + expandVertically(tween(180)), exit = fadeOut(tween(120)) + shrinkVertically(tween(160))) {
                Text(stage.explanation, Modifier.padding(top = 6.dp), fontFamily = AppFonts.body, fontSize = 12.sp, lineHeight = 17.sp, color = Color.White.copy(alpha = .55f))
            }
        }
    }
}
