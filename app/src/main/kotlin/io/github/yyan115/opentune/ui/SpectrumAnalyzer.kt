package io.github.yyan115.opentune.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yyan115.opentune.R
import io.github.yyan115.opentune.core.Spectrum
import io.github.yyan115.opentune.playback.AudioSpectrum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.roundToInt

private const val LOW_HZ = 25f

/**
 * A spectrum analyzer of square cells in one colour. The FFT runs off the main thread about sixty times a second and writes into
 * arrays that the drawing reads, so the screen redraws without recomposing. Each column rises at once and falls slowly, with a held peak.
 * The highest frequency that carries signal is measured too: a lossy stream stops near 16 to 20 kHz, while lossless keeps going.
 *
 * With [fill] the cells grow to fill the height that the parent gives, and [showScale] adds the decibel scale at the left.
 * The full-screen view uses both, with more columns and rows.
 */
@Composable
fun SpectrumAnalyzer(
    playing: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    columns: Int = 40,
    rows: Int = 16,
    fill: Boolean = false,
    showScale: Boolean = false,
) {
    val shown = remember(columns) { FloatArray(columns) }
    val target = remember(columns) { FloatArray(columns) }
    val peaks = remember(columns) { FloatArray(columns) }
    var frame by remember { mutableIntStateOf(0) }
    var nyquist by remember { mutableIntStateOf(22_050) }
    var content by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { AudioSpectrum.watch(); onDispose { AudioSpectrum.release() } }
    LaunchedEffect(playing, columns) {
        withContext(Dispatchers.Default) {
            val samples = FloatArray(Spectrum.SIZE); val re = FloatArray(Spectrum.SIZE / 2); val im = FloatArray(Spectrum.SIZE / 2)
            val power = FloatArray(Spectrum.SIZE / 2); val fresh = FloatArray(columns)
            var highest = 0f
            while (isActive) {
                if (playing && AudioSpectrum.latest(samples)) {
                    val rate = AudioSpectrum.sampleRate
                    Spectrum.bands(samples, rate, fresh, columns, LOW_HZ, rate / 2f, re, im, power)
                    System.arraycopy(fresh, 0, target, 0, columns)
                    // The highest bin clearly above the noise floor, held for a moment so that the readout does not flicker.
                    var top = 0
                    for (bin in power.size - 1 downTo 1) if (Spectrum.level(power[bin]) > .1f) { top = bin; break }
                    val hz = top * rate.toFloat() / Spectrum.SIZE
                    highest = if (hz >= highest) hz else highest * .985f + hz * .015f
                    nyquist = rate / 2
                    content = highest.toInt()
                } else for (i in 0 until columns) target[i] = 0f
                delay(16)
            }
        }
    }
    LaunchedEffect(columns) {
        while (isActive) withFrameNanos {
            for (i in 0 until columns) {
                val t = target[i]
                shown[i] = if (t > shown[i]) shown[i] + (t - shown[i]) * .6f else shown[i] - minOf(shown[i] - t, .018f)
                peaks[i] = if (shown[i] >= peaks[i]) shown[i] else (peaks[i] - .006f).coerceAtLeast(0f)
            }
            frame++
        }
    }
    Column(modifier) {
        Row(if (fill) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth()) {
            if (showScale) DecibelScale(color, Modifier.width(34.dp).fillMaxHeight())
            Canvas((if (fill) Modifier.weight(1f).fillMaxHeight() else Modifier.weight(1f).aspectRatio(columns / rows.toFloat())).testTag("spectrum")) {
                frame // Reading the frame here redraws the canvas on each frame without recomposing anything.
                val pitch = size.width / columns
                val gap = (pitch * .16f).coerceAtLeast(1f)
                val cell = pitch - gap
                val rowPitch = size.height / rows
                val dim = color.copy(alpha = .07f)
                for (column in 0 until columns) {
                    val lit = (shown[column] * rows).roundToInt()
                    val peak = (peaks[column] * rows).roundToInt().coerceAtMost(rows) - 1
                    val x = column * pitch + gap / 2
                    for (row in 0 until rows) {
                        val y = size.height - (row + 1) * rowPitch + gap / 2
                        drawRect(when { row < lit -> color; row == peak && peak >= lit -> color.copy(alpha = .55f); else -> dim }, Offset(x, y), Size(cell, rowPitch - gap))
                    }
                }
            }
        }
        // The frequency axis: each label sits where its frequency falls on the logarithmic scale of the columns.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = if (showScale) 34.dp else 0.dp).padding(top = 6.dp).height(14.dp)) {
            listOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f).filter { it < nyquist }.forEach { hz ->
                val fraction = (ln(hz / LOW_HZ) / ln(nyquist / LOW_HZ)).coerceIn(0f, 1f)
                Text(if (hz >= 1000) "${(hz / 1000).toInt()}k" else hz.toInt().toString(), Modifier.offset(x = maxWidth * fraction - 8.dp), fontFamily = AppFonts.mono, fontSize = 9.sp, color = color.copy(alpha = .5f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.path_content_label), Modifier.weight(1f), fontFamily = AppFonts.body, fontSize = 12.sp, color = color.copy(alpha = .6f))
            Text(if (content > 0) stringResource(R.string.path_content_value, "%.1f".format(content / 1000f)) else stringResource(R.string.path_content_none),
                fontFamily = AppFonts.mono, fontSize = 12.sp, color = color)
        }
    }
}

/** The scale at the left: 0 dB at the top and the analyzer's floor of -84 dB at the bottom. */
@Composable
private fun DecibelScale(color: Color, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        listOf(0, -20, -40, -60).forEach { db ->
            val fraction = (-db) / 84f
            Text(if (db == 0) "0 dB" else "$db", Modifier.offset(y = maxHeight * fraction - 6.dp), fontFamily = AppFonts.mono, fontSize = 8.sp, color = color.copy(alpha = .45f))
        }
    }
}
