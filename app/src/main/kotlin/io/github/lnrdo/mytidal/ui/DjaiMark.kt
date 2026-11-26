package io.github.lnrdo.mytidal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lnrdo.mytidal.playback.dj.DjMix
import io.github.lnrdo.mytidal.playback.dj.DjPhase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** The gold of D'JAI, for the spark when a mix is near and for the mix while it runs. */
val DjaiGold = Color(0xFFD6B56A)

/**
 * The D'JAI mark, alive. It is the icon (two records that overlap, the overlap filled, a spark above) and it shows what D'JAI does:
 *  - while music plays the two records turn, each with a mark near its edge, at 33⅓ turns a minute;
 *  - the overlap, the mix, beats softly on the tempo of the song that plays;
 *  - when a mix is planned the spark turns gold and shines;
 *  - while the mix runs the two records slide into each other with its progress, and the overlap glows gold, until they are one.
 * Everything moves in the draw phase from one frame clock, so it recomposes nothing, and it stands still when the music stops or [live] is false.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun DjaiMark(size: Dp, modifier: Modifier = Modifier, live: Boolean = true, playing: Boolean = true, tint: Color = LocalContentColor.current) {
    val ui by DjMix.ui.collectAsStateWithLifecycle()
    val time = remember { mutableFloatStateOf(0f) }
    val merge = remember { mutableFloatStateOf(0f) }
    val moving = live && playing
    LaunchedEffect(moving) {
        if (!moving) return@LaunchedEffect
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) time.floatValue += ((now - last) / 1e9f).coerceAtMost(.1f)
            last = now
            // The records meet as the mix goes on; they part again softly when it ends.
            val target = if (DjMix.ui.value.phase == DjPhase.MIXING) DjMix.mixer.progress.coerceIn(0f, 1f) else 0f
            merge.floatValue += (target - merge.floatValue) * .12f
        }
    }
    val planned = live && ui.phase == DjPhase.PLANNED
    val mixing = live && ui.phase == DjPhase.MIXING
    val bpm = (ui.bpmOut ?: 120.0).toFloat().coerceIn(60f, 200f)
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val t = time.floatValue
        val m = merge.floatValue
        val stroke = 1.5f * u
        // The two records: 6.8 units apart at rest, one when the mix is done.
        val gap = (6.8f * (1f - m)) * u
        val cy = 13.6f * u
        val r = 6.2f * u
        val left = Offset(12f * u - gap / 2, cy)
        val right = Offset(12f * u + gap / 2, cy)
        drawCircle(tint, r, left, style = Stroke(stroke))
        drawCircle(tint, r, right, style = Stroke(stroke))
        // The mix: the overlap of the two discs, which beats on the tempo — a quick rise and a slow fall on each beat.
        val beat = (t * bpm / 60f) % 1f
        val pulse = if (moving) exp(-beat * 5f) else 0f
        val lens = Path().apply {
            op(Path().apply { addOval(androidx.compose.ui.geometry.Rect(left, r)) }, Path().apply { addOval(androidx.compose.ui.geometry.Rect(right, r)) }, PathOperation.Intersect)
        }
        drawPath(lens, if (mixing) lerpColor(tint, DjaiGold, .35f + .65f * m) else tint, alpha = .82f + .18f * pulse)
        // The marks on the records turn: 33⅓ turns a minute, the incoming deck the other way.
        val turn = if (moving) t * (100f / 3f) / 60f * 360f else 0f
        fun mark(center: Offset, degrees: Float) {
            val a = degrees * PI.toFloat() / 180f
            drawCircle(tint, 1f * u, Offset(center.x + cos(a) * 3.9f * u, center.y + sin(a) * 3.9f * u))
        }
        mark(left, 180f + turn)
        mark(right, -turn)
        // The spark: it breathes slowly, turns a little, and shines gold when a mix is near or runs.
        val spark = Offset(19.6f * u, 4.4f * u)
        val breathe = if (moving) .88f + .12f * sin(t * 2.2f) else 1f
        val shine = if (planned || mixing) 1.15f else 1f
        val sparkColor = if (planned || mixing) DjaiGold else tint
        rotate(if (moving) sin(t * .9f) * 12f else 0f, spark) {
            val s = 3f * u * breathe * shine
            val w = .32f * s
            val star = Path().apply {
                moveTo(spark.x, spark.y - s)
                cubicTo(spark.x + w * .3f, spark.y - w, spark.x + w, spark.y - w * .3f, spark.x + s, spark.y)
                cubicTo(spark.x + w, spark.y + w * .3f, spark.x + w * .3f, spark.y + w, spark.x, spark.y + s)
                cubicTo(spark.x - w * .3f, spark.y + w, spark.x - w, spark.y + w * .3f, spark.x - s, spark.y)
                cubicTo(spark.x - w, spark.y - w * .3f, spark.x - w * .3f, spark.y - w, spark.x, spark.y - s)
                close()
            }
            if (planned || mixing) drawCircle(DjaiGold.copy(alpha = .22f * breathe), s * 1.5f, spark)
            drawPath(star, sparkColor)
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))
