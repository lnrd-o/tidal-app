package io.github.yyan115.opentune.ui

import androidx.annotation.StringRes
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.yyan115.opentune.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The ways to draw the sound. [id] is what the settings keep. [axis] says whether the frequency axis under the picture means something for it. All of them use the one accent colour. */
enum class SpectrumStyle(val id: String, @StringRes val nameRes: Int, @StringRes val hintRes: Int, val axis: Boolean = false) {
    Blocks("blocks", R.string.spectrum_blocks, R.string.spectrum_blocks_text, axis = true),
    Bars("bars", R.string.spectrum_bars, R.string.spectrum_bars_text, axis = true),
    Wave("wave", R.string.spectrum_wave, R.string.spectrum_wave_text, axis = true),
    Scope("scope", R.string.spectrum_scope, R.string.spectrum_scope_text),
    Flower("flower", R.string.spectrum_flower, R.string.spectrum_flower_text),
    Dots("dots", R.string.spectrum_dots, R.string.spectrum_dots_text, axis = true);

    /** The style that follows this one, going round at the end. */
    fun next() = entries[(ordinal + 1) % entries.size]

    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } ?: Blocks }
}

private const val WAVE_SIZE = 512

/** What the styles draw from: the columns of the spectrum ([shown] and [peaks] belong to the analyzer) and the wave of the sound. [step] moves the time on at each frame. */
class VisualData(val columns: Int, val shown: FloatArray, val peaks: FloatArray) {
    val wave = FloatArray(WAVE_SIZE)
    var time = 0f
    var bass = 0f

    fun step(dt: Float) {
        time += dt
        val to = max(1, (columns * .12f).toInt())
        var sum = 0f
        for (i in 0 until to) sum += shown[i]
        bass = sum / to
    }
}

/** Draws [data] in [style], in [color]. */
fun DrawScope.drawVisual(style: SpectrumStyle, data: VisualData, color: Color) {
    when (style) {
        SpectrumStyle.Blocks -> Unit
        SpectrumStyle.Bars -> bars(data, color)
        SpectrumStyle.Wave -> singleWave(data, color)
        SpectrumStyle.Scope -> scope(data, color)
        SpectrumStyle.Flower -> flower(data, color)
        SpectrumStyle.Dots -> dots(data, color)
    }
}

private fun DrawScope.bars(d: VisualData, color: Color) {
    val n = d.columns; val pitch = size.width / n; val base = size.height * .78f; val room = base * .96f
    for (i in 0 until n) {
        val x = i * pitch + pitch * .15f; val bar = pitch * .7f
        val h = (d.shown[i] * room).coerceAtLeast(2f)
        drawRoundRect(Brush.verticalGradient(listOf(color, color.copy(alpha = .35f)), base - h, base), Offset(x, base - h), Size(bar, h), CornerRadius(bar / 2))
        // The reflection under the line.
        drawRoundRect(Brush.verticalGradient(listOf(color.copy(alpha = .22f), Color.Transparent), base, base + h * .5f), Offset(x, base), Size(bar, h * .5f), CornerRadius(bar / 2))
        val peak = d.peaks[i] * room
        drawRoundRect(color.copy(alpha = .7f), Offset(x, base - peak - bar), Size(bar, bar * .8f), CornerRadius(bar / 2))
    }
}

/** A smooth line through [count] points, made of quadratic curves between their middles. */
private fun smoothPath(count: Int, x: (Int) -> Float, y: (Int) -> Float): Path {
    val path = Path()
    path.moveTo(x(0), y(0))
    for (i in 1 until count) path.quadraticTo(x(i - 1), y(i - 1), (x(i - 1) + x(i)) / 2f, (y(i - 1) + y(i)) / 2f)
    path.lineTo(x(count - 1), y(count - 1))
    return path
}

private fun DrawScope.singleWave(d: VisualData, color: Color) {
    val n = d.columns; val base = size.height * .8f
    val line = smoothPath(n, { it * size.width / (n - 1) }, { base - d.shown[it] * base * .95f })
    val fill = Path().apply { addPath(line); lineTo(size.width, base); lineTo(0f, base); close() }
    drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = .45f), Color.Transparent), 0f, base))
    drawPath(line, color.copy(alpha = .25f), style = Stroke(10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(line, color, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.scope(d: VisualData, color: Color) {
    // The trace starts at a rising crossing of zero, so that the picture stands still.
    var start = 0
    for (i in 1 until WAVE_SIZE / 2) if (d.wave[i - 1] < 0f && d.wave[i] >= 0f) { start = i; break }
    var peak = .05f
    for (i in 0 until WAVE_SIZE) peak = max(peak, abs(d.wave[i]))
    val count = WAVE_SIZE - start - 1
    val path = smoothPath(count, { it * size.width / (count - 1) }, { size.height / 2f - d.wave[start + it] / peak * size.height * .42f })
    drawPath(path, color.copy(alpha = .22f), style = Stroke(12.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawLine(color.copy(alpha = .12f), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.dp.toPx())
}

private fun DrawScope.flower(d: VisualData, color: Color) {
    val c = Offset(size.width / 2f, size.height / 2f); val side = min(size.width, size.height) * .45f
    for (layer in 0 until 2) {
        val path = Path()
        val steps = 180
        for (s in 0..steps) {
            val a = 2f * PI.toFloat() * s / steps
            val e = d.shown[(abs(s - steps / 2) * (d.columns - 1)) / (steps / 2)]
            val petal = .72f + .28f * abs(cos(a * (6f + layer * 2f)))
            val r = side * (.3f + e * .7f) * petal * (1f - layer * .35f) * (1f + d.bass * .12f)
            val ang = a + d.time * (.25f - layer * .5f)
            val x = c.x + cos(ang) * r; val y = c.y + sin(ang) * r
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color.copy(alpha = .16f))
        drawPath(path, color.copy(alpha = if (layer == 0) .95f else .6f), style = Stroke(1.5f.dp.toPx(), join = StrokeJoin.Round))
    }
}

private fun DrawScope.dots(d: VisualData, color: Color) {
    val n = d.columns; val pitch = size.width / n; val r = pitch * .32f; val mid = size.height * .45f; val steps = 18
    val stepH = mid * .95f / steps
    for (i in 0 until n) {
        val lit = (d.shown[i] * steps).toInt()
        val x = i * pitch + pitch / 2f
        for (s in 0 until lit) {
            val fade = 1f - s / steps.toFloat() * .55f
            drawCircle(color.copy(alpha = fade), r, Offset(x, mid - s * stepH))
            drawCircle(color.copy(alpha = fade * .35f), r, Offset(x, mid + s * stepH))
        }
        drawCircle(color.copy(alpha = .15f), r * .6f, Offset(x, mid))
    }
}
