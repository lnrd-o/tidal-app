package io.github.yyan115.opentune.ui

import androidx.annotation.StringRes
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import io.github.yyan115.opentune.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** The ways to draw the sound. [id] is what the settings keep. [axis] says whether the frequency axis under the picture means something for it. */
enum class SpectrumStyle(val id: String, @StringRes val nameRes: Int, @StringRes val hintRes: Int, val axis: Boolean = false) {
    Blocks("blocks", R.string.spectrum_blocks, R.string.spectrum_blocks_text, axis = true),
    Bars("bars", R.string.spectrum_bars, R.string.spectrum_bars_text, axis = true),
    Wave("wave", R.string.spectrum_wave, R.string.spectrum_wave_text, axis = true),
    Multiwave("multiwave", R.string.spectrum_multiwave, R.string.spectrum_multiwave_text),
    Terrain("terrain", R.string.spectrum_terrain, R.string.spectrum_terrain_text),
    Field("field", R.string.spectrum_field, R.string.spectrum_field_text),
    Scope("scope", R.string.spectrum_scope, R.string.spectrum_scope_text),
    Radial("radial", R.string.spectrum_radial, R.string.spectrum_radial_text),
    Rings("rings", R.string.spectrum_rings, R.string.spectrum_rings_text),
    Ambience("ambience", R.string.spectrum_ambience, R.string.spectrum_ambience_text),
    Fireworks("fireworks", R.string.spectrum_fireworks, R.string.spectrum_fireworks_text),
    Flower("flower", R.string.spectrum_flower, R.string.spectrum_flower_text),
    Vu("vu", R.string.spectrum_vu, R.string.spectrum_vu_text),
    Dots("dots", R.string.spectrum_dots, R.string.spectrum_dots_text, axis = true);

    /** The style that follows this one, going round at the end. */
    fun next() = entries[(ordinal + 1) % entries.size]

    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } ?: Blocks }
}

private const val HISTORY = 30
private const val WAVE_SIZE = 512
private const val PARTICLES = 220

/**
 * What the styles draw from: the columns of the spectrum ([shown] and [peaks] belong to the analyzer), the wave of the sound, its two channels, a short
 * history of the spectrum, and the state of the moving styles (the needles, the sparks). The analyzer fills it; [step] moves it on at each frame.
 */
class VisualData(val columns: Int, val shown: FloatArray, val peaks: FloatArray) {
    val wave = FloatArray(WAVE_SIZE)
    val left = FloatArray(WAVE_SIZE)
    val right = FloatArray(WAVE_SIZE)
    val history = Array(HISTORY) { FloatArray(columns) }
    private var head = 0
    private var sinceRow = 0f
    var time = 0f
    var bass = 0f; var mid = 0f; var treble = 0f
    private var bassSlow = 0f
    private var cooldown = 0f
    var needleLeft = 0f; var needleRight = 0f
    // The sparks, in fractions of the picture.
    private val px = FloatArray(PARTICLES); private val py = FloatArray(PARTICLES); private val pvx = FloatArray(PARTICLES); private val pvy = FloatArray(PARTICLES)
    private val plife = FloatArray(PARTICLES); private val phue = FloatArray(PARTICLES)
    private var next = 0
    private val random = Random(7)

    fun average(from: Float, to: Float): Float {
        val a = (from * columns).toInt().coerceIn(0, columns - 1); val b = (to * columns).toInt().coerceIn(a + 1, columns)
        var sum = 0f
        for (i in a until b) sum += shown[i]
        return sum / (b - a)
    }

    /** The row of the history that is [age] rows old: 0 is the newest. */
    fun row(age: Int) = history[(head - age + HISTORY * 2) % HISTORY]

    fun step(dt: Float) {
        time += dt
        bass = average(0f, .12f); mid = average(.12f, .5f); treble = average(.5f, 1f)
        // A kick is a rise of the bass over what it was a moment ago.
        bassSlow += (bass - bassSlow) * min(1f, dt * 3f)
        cooldown -= dt
        val kick = bass - bassSlow > .12f && cooldown <= 0f
        if (kick) { cooldown = .22f; burst(bass) }
        sinceRow += dt
        if (sinceRow >= .06f) { sinceRow = 0f; head = (head + 1) % HISTORY; System.arraycopy(shown, 0, history[head], 0, columns) }
        var l = 0f; var r = 0f
        for (i in 0 until WAVE_SIZE) { l += left[i] * left[i]; r += right[i] * right[i] }
        val level = { sum: Float -> (sqrt(sum / WAVE_SIZE) * 2.6f).coerceIn(0f, 1f) }
        // A needle is quick up and slow down, like the coil of a meter.
        val tl = level(l); val tr = level(r)
        needleLeft += (tl - needleLeft) * min(1f, dt * if (tl > needleLeft) 14f else 4f)
        needleRight += (tr - needleRight) * min(1f, dt * if (tr > needleRight) 14f else 4f)
        for (i in 0 until PARTICLES) if (plife[i] > 0f) {
            plife[i] -= dt * .8f
            px[i] += pvx[i] * dt; py[i] += pvy[i] * dt; pvy[i] += .5f * dt; pvx[i] *= 1f - dt * .6f; pvy[i] *= 1f - dt * .3f
        }
    }

    private fun burst(strength: Float) {
        val cx = .15f + random.nextFloat() * .7f; val cy = .15f + random.nextFloat() * .45f; val hue = random.nextFloat() * 360f
        val count = 18 + (strength * 26).toInt()
        repeat(count) {
            val i = next; next = (next + 1) % PARTICLES
            val angle = random.nextFloat() * 2f * PI.toFloat(); val speed = (.12f + random.nextFloat() * .3f) * (.6f + strength)
            px[i] = cx; py[i] = cy; pvx[i] = cos(angle) * speed; pvy[i] = sin(angle) * speed; plife[i] = 1f; phue[i] = hue + random.nextFloat() * 40f
        }
    }

    fun forEachSpark(action: (x: Float, y: Float, vx: Float, vy: Float, life: Float, hue: Float) -> Unit) {
        for (i in 0 until PARTICLES) if (plife[i] > 0f) action(px[i], py[i], pvx[i], pvy[i], plife[i], phue[i])
    }
}

private fun hue(h: Float, s: Float = .65f, v: Float = 1f) = Color.hsv((((h % 360f) + 360f) % 360f), s, v)

/** Draws [data] in [style]. [color] is the accent of the player, and the styles that are made of many colours take their hues from the music instead. */
fun DrawScope.drawVisual(style: SpectrumStyle, data: VisualData, color: Color) {
    when (style) {
        SpectrumStyle.Blocks -> Unit
        SpectrumStyle.Bars -> bars(data, color)
        SpectrumStyle.Wave -> singleWave(data, color)
        SpectrumStyle.Multiwave -> multiWave(data, color)
        SpectrumStyle.Terrain -> terrain(data, color)
        SpectrumStyle.Field -> stereoField(data)
        SpectrumStyle.Scope -> scope(data, color)
        SpectrumStyle.Radial -> radial(data)
        SpectrumStyle.Rings -> rings(data)
        SpectrumStyle.Ambience -> ambience(data)
        SpectrumStyle.Fireworks -> fireworks(data)
        SpectrumStyle.Flower -> flower(data)
        SpectrumStyle.Vu -> vuMeters(data)
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

private fun DrawScope.multiWave(d: VisualData, color: Color) {
    val steps = 90; val mid = size.height / 2f
    for (k in 0 until 5) {
        val band = d.average(k * .2f, k * .2f + .2f)
        val path = smoothPath(steps, { it * size.width / (steps - 1) }, { i ->
            val u = i / (steps - 1f)
            val local = d.shown[(u * (d.columns - 1)).toInt()]
            // The edges are pinned to the middle, and the heart of the wave swings with the energy of its own band and of the bass.
            val envelope = sin(u * PI.toFloat())
            mid + sin(u * (4f + k * 2.2f) * PI.toFloat() + d.time * (1.2f + k * .45f) + k) * (local * .6f + band * .5f + d.bass * .25f) * size.height * .42f * envelope
        })
        val tint = if (color == Color.White) hue(190f + k * 28f, .55f) else color.copy(alpha = 1f - k * .14f)
        drawPath(path, tint.copy(alpha = .18f), style = Stroke(9.dp.toPx(), cap = StrokeCap.Round))
        drawPath(path, tint, style = Stroke((3.2f - k * .3f).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** The last seconds of the spectrum as rows in perspective, the newest in front, each hiding what is behind it. */
private fun DrawScope.terrain(d: VisualData, color: Color) {
    val points = min(d.columns, 48)
    for (age in HISTORY - 1 downTo 0) {
        val z = 1f - age / (HISTORY - 1f)
        val row = d.row(age)
        val width = size.width * (.4f + .6f * z); val left = (size.width - width) / 2f
        val base = size.height * (.3f + .62f * z); val peak = size.height * .42f * (.35f + .65f * z)
        val path = smoothPath(points, { left + it * width / (points - 1) }, { i -> base - row[(i * (d.columns - 1)) / (points - 1)] * peak })
        val fill = Path().apply { addPath(path); lineTo(left + width, size.height); lineTo(left, size.height); close() }
        drawPath(fill, Color.Black)
        drawPath(path, (if (color == Color.White) hue(200f + z * 120f, .45f) else color).copy(alpha = .2f + .8f * z), style = Stroke((.8f + 1.6f * z).dp.toPx(), join = StrokeJoin.Round))
    }
}

/** Where the sound comes from: each sample is a point, to the left or right by how different the channels are, up by how alike. Old samples fall into depth. */
private fun DrawScope.stereoField(d: VisualData) {
    val cx = size.width / 2f; val cy = size.height * .55f; val reach = min(size.width * .5f, size.height * .5f) * .95f
    // The frame: the two diagonals are the left and the right channel, and the vertical is the centre.
    val axis = Color.White.copy(alpha = .12f)
    drawLine(axis, Offset(cx, cy - reach), Offset(cx, cy + reach * .2f), 1.dp.toPx())
    drawLine(axis, Offset(cx - reach * .7f, cy + reach * .15f), Offset(cx + reach * .7f, cy - reach * .85f), 1.dp.toPx())
    drawLine(axis, Offset(cx + reach * .7f, cy + reach * .15f), Offset(cx - reach * .7f, cy - reach * .85f), 1.dp.toPx())
    val ring = Stroke(1.dp.toPx())
    drawCircle(axis, reach, Offset(cx, cy), style = ring); drawCircle(axis, reach * .5f, Offset(cx, cy), style = ring)
    val gain = 1.6f
    for (i in 0 until WAVE_SIZE step 2) {
        val age = i / (WAVE_SIZE - 1f) // 0 is the oldest
        val side = (d.left[i] - d.right[i]) * .7071f * gain; val up = (d.left[i] + d.right[i]) * .7071f * gain
        // Perspective: the old points are nearer the vanishing point, small and faint.
        val depth = .45f + .55f * age
        val x = cx + side.coerceIn(-1f, 1f) * reach * depth
        val y = cy - up.coerceIn(-1f, 1f) * reach * depth - (1f - depth) * reach * .35f
        drawCircle(hue(200f + side * 160f, .6f).copy(alpha = .15f + .85f * age), (1f + 2.6f * age).dp.toPx(), Offset(x, y), blendMode = BlendMode.Plus)
    }
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

private fun DrawScope.radial(d: VisualData) {
    val c = Offset(size.width / 2f, size.height / 2f); val side = min(size.width, size.height)
    val inner = side * .2f * (1f + d.bass * .3f); val n = d.columns
    rotate(d.time * 8f, c) {
        for (i in 0 until n) {
            val idx = if (i < n / 2) i * 2 else (n - 1 - i) * 2
            val e = d.shown[idx.coerceIn(0, n - 1)]
            val angle = (2f * PI.toFloat() * i / n) - PI.toFloat() / 2f
            val length = side * .04f + e * side * .27f
            val dx = cos(angle); val dy = sin(angle)
            drawLine(hue(200f + 160f * i / n), Offset(c.x + dx * inner, c.y + dy * inner), Offset(c.x + dx * (inner + length), c.y + dy * (inner + length)), 3.dp.toPx(), StrokeCap.Round)
        }
    }
    drawCircle(hue(210f).copy(alpha = .5f), inner - 4.dp.toPx(), c, style = Stroke(2.dp.toPx()))
}

private fun DrawScope.rings(d: VisualData) {
    val c = Offset(size.width / 2f, size.height / 2f); val max = min(size.width, size.height) * .48f; val n = 12
    for (i in 0 until n) {
        val e = d.shown[(i * (d.columns - 1)) / (n - 1)]
        val r = (i + 1f) / n * max * (1f + e * .12f)
        drawCircle(hue(i * 26f + d.time * 20f).copy(alpha = .25f + .75f * e), r, c, style = Stroke((1f + e * 9f).dp.toPx()))
    }
}

private fun DrawScope.ambience(d: VisualData) {
    val side = min(size.width, size.height)
    for (k in 0 until 6) {
        val e = d.average(k / 6f, (k + 1) / 6f)
        val x = size.width * (.5f + .36f * sin(d.time * .23f * (k + 1) + k * 1.7f)); val y = size.height * (.5f + .34f * cos(d.time * .19f * (k + 2) + k))
        val r = side * (.2f + .3f * e)
        val tint = hue(k * 55f + d.time * 12f, .7f)
        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = .75f), Color.Transparent), Offset(x, y), r), r, Offset(x, y), blendMode = BlendMode.Plus)
    }
}

private fun DrawScope.fireworks(d: VisualData) {
    d.forEachSpark { x, y, vx, vy, life, h ->
        val p = Offset(x * size.width, y * size.height)
        val tail = Offset((x - vx * .06f) * size.width, (y - vy * .06f) * size.height)
        val tint = hue(h, .55f).copy(alpha = life.coerceIn(0f, 1f))
        drawLine(tint.copy(alpha = tint.alpha * .5f), tail, p, 2.dp.toPx(), StrokeCap.Round)
        drawCircle(tint, (1.5f + life * 2f).dp.toPx(), p, blendMode = BlendMode.Plus)
    }
    // A low glow at the bottom that follows the bass.
    drawRect(Brush.verticalGradient(listOf(Color.Transparent, hue(d.time * 15f, .6f).copy(alpha = .25f * d.bass)), size.height * .6f, size.height))
}

private fun DrawScope.flower(d: VisualData) {
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
        val tint = hue(300f - layer * 120f + d.time * 10f, .6f)
        drawPath(path, tint.copy(alpha = .28f), blendMode = BlendMode.Plus)
        drawPath(path, tint.copy(alpha = .9f), style = Stroke(1.5f.dp.toPx(), join = StrokeJoin.Round))
    }
}

private fun DrawScope.vuMeters(d: VisualData) {
    val gap = 14.dp.toPx(); val panelW = (size.width - gap * 3f) / 2f; val panelH = min(size.height * .8f, panelW * .75f)
    val top = (size.height - panelH) / 2f
    for (m in 0 until 2) {
        val level = if (m == 0) d.needleLeft else d.needleRight
        val x0 = gap + m * (panelW + gap)
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFFFE9A8), Color(0xFFE8B95A)), top, top + panelH), Offset(x0, top), Size(panelW, panelH), CornerRadius(8.dp.toPx()))
        drawRoundRect(Color(0xFF1A1208), Offset(x0, top), Size(panelW, panelH), CornerRadius(8.dp.toPx()), style = Stroke(3.dp.toPx()))
        val pivot = Offset(x0 + panelW / 2f, top + panelH * 1.02f); val radius = panelH * .92f
        val sweep = 54f
        fun at(fraction: Float, length: Float, tail: Float = 0f): Pair<Offset, Offset> {
            val a = Math.toRadians((-90f - sweep + 2f * sweep * fraction).toDouble())
            return Offset(pivot.x + cos(a).toFloat() * (length - tail), pivot.y + sin(a).toFloat() * (length - tail)) to Offset(pivot.x + cos(a).toFloat() * length, pivot.y + sin(a).toFloat() * length)
        }
        // The scale: ticks, with the last part in red.
        for (t in 0..20) {
            val f = t / 20f; val long = t % 5 == 0
            val (from, to) = at(f, radius, if (long) radius * .13f else radius * .07f)
            drawLine(if (f > .72f) Color(0xFFB3261E) else Color(0xFF2A1D0C), from, to, (if (long) 2.5f else 1.2f).dp.toPx())
        }
        val (_, tip) = at(level, radius * .98f)
        drawLine(Color(0xFF14100A), pivot, tip, 2.dp.toPx(), StrokeCap.Round)
        drawCircle(Color(0xFF14100A), panelH * .07f, pivot)
        // The peak light.
        drawCircle(if (level > .86f) Color(0xFFFF3B30) else Color(0xFF5A1A16), 5.dp.toPx(), Offset(x0 + panelW - 16.dp.toPx(), top + 16.dp.toPx()))
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
