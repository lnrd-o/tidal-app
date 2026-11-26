package io.github.lnrdo.mytidal.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.lnrdo.mytidal.R
import io.github.lnrdo.mytidal.core.effects.DotShape
import io.github.lnrdo.mytidal.core.effects.EffectOptions
import io.github.lnrdo.mytidal.core.effects.ImageEffects
import io.github.lnrdo.mytidal.core.effects.ImageStyle
import io.github.lnrdo.mytidal.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/** The settings of the effects only, as a draft that the page edits and sends to the settings at most once a frame. */
private data class EffectDraft(
    val style: String, val cell: Int, val contrast: Float, val brightness: Int, val angle: Int, val shape: String, val duotone: Boolean, val shadow: Int, val light: Int,
) {
    fun options() = EffectOptions(ImageStyle.of(style), cell, contrast, brightness, angle, DotShape.of(shape), duotone, shadow, light).clamped()
    fun into(s: Settings) = s.copy(imageStyle = style, imageCell = cell, imageContrast = contrast, imageBrightness = brightness, imageAngle = angle, imageShape = shape,
        imageDuotone = duotone, imageShadow = shadow, imageLight = light)

    companion object {
        fun of(s: Settings) = EffectDraft(s.imageStyle, s.imageCell, s.imageContrast, s.imageBrightness, s.imageAngle, s.imageShape, s.imageDuotone, s.imageShadow, s.imageLight)
        fun of(o: EffectOptions) = EffectDraft(o.style.id, o.cellSize, o.contrast, o.brightness, o.gridAngle, o.shape.id, o.duotone, o.shadow, o.light)
        val DEFAULT = of(EffectOptions())
    }
}

private val StyleNames = mapOf(ImageStyle.NONE to R.string.fx_none, ImageStyle.HALFTONE to R.string.fx_halftone, ImageStyle.DITHER to R.string.fx_dither, ImageStyle.PIXEL to R.string.fx_pixel)
private val ShapeNames = mapOf(DotShape.CIRCLE to R.string.fx_circle, DotShape.SQUARE to R.string.fx_square, DotShape.DIAMOND to R.string.fx_diamond)
private val PresetNames = mapOf("blue_dither" to R.string.fx_preset_blue, "beige_pixel" to R.string.fx_preset_beige, "black_dot" to R.string.fx_preset_black)

/**
 * How the app is drawn: the mode (as it is, halftone, dither or pixel), its options, presets and a live preview. The whole app shows each change at once;
 * the page keeps a draft and passes it to the settings at most once a frame, so a slider that moves does not write the settings on every step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun ImageEffectsPage(state: AppState, vm: MainViewModel) {
    var draft by remember { mutableStateOf(EffectDraft.of(state.settings)) }
    LaunchedEffect(Unit) {
        snapshotFlow { draft }.collectLatest { d -> withFrameNanos { }; vm.settings { d.into(it) } }
    }
    var picking by remember { mutableStateOf<Int?>(null) } // 0 the shadow colour, 1 the light colour
    val style = ImageStyle.of(draft.style)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { EffectPreview(state.playback.track?.artwork, draft.options()) }
        if (!canDrawEffects) item {
            Text(stringResource(R.string.fx_needs_13), Modifier.padding(horizontal = 24.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { SectionTitle(stringResource(R.string.fx_section_mode)) }
        item {
            Segmented(ImageStyle.entries, style, { stringResource(StyleNames.getValue(it)) }) { draft = draft.copy(style = it.id) }
        }
        item { SectionTitle(stringResource(R.string.fx_section_presets)) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (preset in ImageEffects.PRESETS) FilterChip(draft == EffectDraft.of(preset.options), { draft = EffectDraft.of(preset.options) },
                    label = { Text(stringResource(PresetNames.getValue(preset.id))) },
                    leadingIcon = { Box(Modifier.size(14.dp).clip(CircleShape).background(Color(preset.options.shadow)).border(2.dp, Color(preset.options.light), CircleShape)) })
            }
        }
        if (style != ImageStyle.NONE) {
            item { SectionTitle(stringResource(R.string.fx_section_options)) }
            item { EffectSlider(stringResource(R.string.fx_cell), "${draft.cell} dp", draft.cell.toFloat(), 2f..40f, 37) { draft = draft.copy(cell = it.toInt()) } }
            item { EffectSlider(stringResource(R.string.fx_contrast), "%.2f".format(draft.contrast), draft.contrast, .4f..3f, 0) { draft = draft.copy(contrast = (it * 20).toInt() / 20f) } }
            item { EffectSlider(stringResource(R.string.fx_brightness), "%+d".format(draft.brightness), draft.brightness.toFloat(), -80f..80f, 0) { draft = draft.copy(brightness = it.toInt()) } }
            if (style == ImageStyle.HALFTONE) item {
                EffectSlider(stringResource(R.string.fx_angle), "${draft.angle}°", draft.angle.toFloat(), 0f..90f, 17) { draft = draft.copy(angle = it.toInt()) }
            }
            if (style == ImageStyle.HALFTONE || style == ImageStyle.PIXEL) {
                item { Label(stringResource(R.string.fx_shape)) }
                item { Segmented(DotShape.entries, DotShape.of(draft.shape), { stringResource(ShapeNames.getValue(it)) }) { draft = draft.copy(shape = it.id) } }
            }
            item { Label(stringResource(R.string.fx_colour_mode)) }
            item { Segmented(listOf(true, false), draft.duotone, { stringResource(if (it) R.string.fx_duotone else R.string.fx_original) }) { draft = draft.copy(duotone = it) } }
            if (draft.duotone) {
                item { ColourRow(stringResource(R.string.fx_shadow), draft.shadow) { picking = 0 } }
                item { ColourRow(stringResource(R.string.fx_light), draft.light) { picking = 1 } }
                if (ImageEffects.contrastRatio(draft.shadow, draft.light) < 4.5) item {
                    Text(stringResource(R.string.fx_contrast_fixed), Modifier.padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            OutlinedButton({ draft = EffectDraft.DEFAULT }, Modifier.padding(horizontal = 24.dp, vertical = 16.dp).fillMaxWidth()) { Text(stringResource(R.string.fx_reset)) }
        }
    }
    picking?.let { which ->
        ColourPicker(if (which == 0) draft.shadow else draft.light, { picking = null }) { colour -> draft = if (which == 0) draft.copy(shadow = colour) else draft.copy(light = colour) }
    }
}

/** The cover that plays (or a built-in gradient) through the effect, on the CPU, so that the preview shows on every phone. It is drawn without smoothing. */
@Composable
private fun EffectPreview(artwork: String?, options: EffectOptions) {
    val context = LocalContext.current
    val source by produceState<Bitmap?>(null, artwork) {
        value = withContext(Dispatchers.IO) {
            artwork?.let { url ->
                runCatching {
                    val result = SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(url).size(480).allowHardware(false).build())
                    (result as? SuccessResult)?.image?.toBitmap()
                }.getOrNull()
            } ?: sampleBitmap()
        }
    }
    val shown by produceState<Bitmap?>(null, source, options) {
        val bitmap = source ?: return@produceState
        value = withContext(Dispatchers.Default) { if (options.style == ImageStyle.NONE) bitmap else bitmap.withEffect(options, minOf(bitmap.width, bitmap.height) / 180f) }
    }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(180.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant)) {
            shown?.let { Image(it.asImageBitmap(), stringResource(R.string.fx_preview), Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.None) }
        }
    }
}

/** A diagonal gradient of light and colour, for the preview when nothing plays. */
private fun sampleBitmap(): Bitmap {
    val size = 480
    val pixels = IntArray(size * size) { i ->
        val x = i % size; val y = i / size
        val t = (x + y) / (2f * size)
        ImageEffects.mix(0xFF1B2A6B.toInt(), 0xFFF2C46B.toInt(), t)
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Segmented(options: List<T>, selected: T, name: @Composable (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        options.forEachIndexed { i, option ->
            SegmentedButton(option == selected, { onSelect(option) }, SegmentedButtonDefaults.itemShape(i, options.size)) { Text(name(option), maxLines = 1, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp), style = MaterialTheme.typography.bodyMedium)
}

/** A slider with its name and its value written beside it as it moves. */
@Composable
private fun EffectSlider(title: String, value: String, current: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(value, fontFamily = AppFonts.mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(current, onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun ColourRow(title: String, colour: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Color(colour)).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
        Text(title, Modifier.weight(1f).padding(start = 14.dp), style = MaterialTheme.typography.bodyMedium)
        Text("#%06X".format(colour and 0xFFFFFF), fontFamily = AppFonts.mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A colour picker: hue, saturation and value, a hexadecimal field, and a row of common colours. */
@Composable
private fun ColourPicker(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val colour = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    var hex by remember(colour) { mutableStateOf("%06X".format(colour and 0xFFFFFF)) }
    fun set(c: Int) { val f = FloatArray(3); android.graphics.Color.colorToHSV(c, f); hue = f[0]; sat = f[1]; value = f[2] }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton({ onPick(colour or (0xFF shl 24)); onDismiss() }) { Text(stringResource(R.string.fx_pick)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp).clip(MaterialTheme.shapes.medium).background(Color(colour)))
                Text(stringResource(R.string.fx_hue)); Slider(hue, { hue = it }, valueRange = 0f..360f)
                Text(stringResource(R.string.fx_saturation)); Slider(sat, { sat = it })
                Text(stringResource(R.string.fx_value)); Slider(value, { value = it })
                OutlinedTextField(hex, { text ->
                    hex = text.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                    if (hex.length == 6) set(0xFF000000.toInt() or hex.toInt(16))
                }, singleLine = true, prefix = { Text("#") }, label = { Text(stringResource(R.string.fx_hex)) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in listOf(0xFF0A0A0A, 0xFFF2F2F2, 0xFF2F5BFF, 0xFF6C8488, 0xFFE8D7AE, 0xFFD6B56A, 0xFFE5484D)) {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(Color(c.toInt())).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable { set(c.toInt()) })
                    }
                }
            }
        })
}
