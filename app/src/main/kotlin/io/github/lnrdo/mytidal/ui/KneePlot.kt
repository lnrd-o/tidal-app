package io.github.lnrdo.mytidal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lnrdo.mytidal.R
import io.github.lnrdo.mytidal.core.dj.Flavor
import io.github.lnrdo.mytidal.core.dj.kneeOf
import io.github.lnrdo.mytidal.playback.dj.DjMix
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min

private const val LOW_DB = -60.0
private const val HIGH_DB = 6.0

/**
 * The transfer curve of the glue, level in against level out on the same scale, redrawn for the flavor and the amount that are set. The dashed diagonal is
 * no compression, the shaded band is the knee, the angle is the slope of the line above it (45 degrees means no compression), and the dot is the level
 * that the glue reads now and the level that it gives back, so that the turning down shows as it happens.
 */
@Composable
fun KneePlot(flavor: Flavor, glue: Float) {
    val knee = remember(flavor, glue) { kneeOf(flavor, glue) }
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (isActive) androidx.compose.runtime.withFrameNanos { frame++ } }
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurface
    val dim = ink.copy(alpha = .5f)
    val smooth = remember { floatArrayOf(-100f, 0f) }
    val label = TextStyle(fontFamily = AppFonts.mono, fontSize = 9.sp, color = dim)
    val elzar = DjMix.mixer.elzar
    frame
    val reduction = elzar.reductionDb
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(stringResource(R.string.elzar_knee).uppercase(), fontFamily = AppFonts.body, fontSize = 10.sp, letterSpacing = 1.4.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.15f).padding(top = 8.dp)) {
            frame
            val margin = 26.dp.toPx()
            val side = min(size.width - margin, size.height - margin)
            val left = margin; val top = 0f
            fun x(db: Double) = left + ((db - LOW_DB) / (HIGH_DB - LOW_DB)).toFloat() * side
            fun y(db: Double) = top + ((HIGH_DB - db) / (HIGH_DB - LOW_DB)).toFloat() * side
            // The grid, every 10 dB, with a label every 20.
            var db = LOW_DB
            while (db <= HIGH_DB) {
                drawLine(ink.copy(alpha = .07f), Offset(x(db), top), Offset(x(db), top + side), 1f)
                drawLine(ink.copy(alpha = .07f), Offset(left, y(db)), Offset(left + side, y(db)), 1f)
                if (db.toInt() % 20 == 0) {
                    val text = "${db.toInt()}"
                    val size = measurer.measure(text, label)
                    drawText(measurer, text, Offset(x(db) - size.size.width / 2f, top + side + 3.dp.toPx()), label)
                    drawText(measurer, text, Offset(left - size.size.width - 4.dp.toPx(), y(db) - size.size.height / 2f), label)
                }
                db += 10.0
            }
            drawRect(ink.copy(alpha = .3f), Offset(left, top), Size(side, side), style = Stroke(1f))
            // The knee, as a band around the threshold, and the threshold itself.
            val half = knee.kneeDb / 2
            drawRect(ink.copy(alpha = .07f), Offset(x(knee.thresholdDb - half), top), Size((x(knee.thresholdDb + half) - x(knee.thresholdDb - half)).coerceAtLeast(2f), side))
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            drawLine(ink.copy(alpha = .35f), Offset(x(knee.thresholdDb), top), Offset(x(knee.thresholdDb), top + side), 1.dp.toPx(), pathEffect = dash)
            // No compression: the diagonal.
            drawLine(dim, Offset(x(LOW_DB), y(LOW_DB)), Offset(x(HIGH_DB), y(HIGH_DB)), 1.dp.toPx(), pathEffect = dash)
            // The curve, as the listener hears it.
            val curve = Path()
            var first = true
            var level = LOW_DB
            while (level <= HIGH_DB) {
                val out = min(knee.outputDb(level), HIGH_DB)
                if (first) { curve.moveTo(x(level), y(out)); first = false } else curve.lineTo(x(level), y(out))
                level += 1.0
            }
            drawPath(curve, ink, style = Stroke(2.5f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            // The angle of the line above the knee, drawn at the point where the knee ends.
            val kneeEnd = min(knee.thresholdDb + half + 14.0, HIGH_DB - 6.0)
            val corner = Offset(x(kneeEnd), y(knee.outputDb(kneeEnd)))
            val radius = 34.dp.toPx()
            drawLine(dim, corner, Offset(corner.x + radius * 1.3f, corner.y), 1.dp.toPx(), pathEffect = dash)
            drawArc(ink, -knee.angleDegrees.toFloat(), knee.angleDegrees.toFloat(), false, Offset(corner.x - radius, corner.y - radius), Size(radius * 2, radius * 2), style = Stroke(1.5f.dp.toPx()))
            val angleText = "%.0f°".format(knee.angleDegrees)
            drawText(measurer, angleText, Offset(corner.x + radius * .55f, corner.y - 17.dp.toPx()), label.copy(color = ink))
            val thr = "%.0f".format(knee.thresholdDb)
            drawText(measurer, thr, Offset(x(knee.thresholdDb) + 3.dp.toPx(), top + 2.dp.toPx()), label)
            // The point that moves: where the music is now, and what the glue gives back. Its distance from the diagonal is the gain reduction.
            val read = elzar.levelDb
            if (read > -80f) {
                smooth[0] += (read - smooth[0]) * if (smooth[0] < -90f) 1f else .45f
                val input = smooth[0].toDouble().coerceIn(LOW_DB, HIGH_DB)
                val output = min(knee.liveOutputDb(input, reduction.toDouble()), HIGH_DB)
                drawLine(ink.copy(alpha = .8f), Offset(x(input), y(input)), Offset(x(input), y(output)), 2.dp.toPx(), StrokeCap.Round)
                drawCircle(Color.Black, 7.dp.toPx(), Offset(x(input), y(output)))
                drawCircle(ink, 5.dp.toPx(), Offset(x(input), y(output)))
            } else smooth[0] = -100f
        }
        Text(stringResource(R.string.elzar_knee_readout, "%.1f".format(knee.thresholdDb), "%.1f".format(knee.ratio), "%.0f".format(knee.kneeDb), "%.0f".format(knee.angleDegrees), "%.1f".format(max(0f, reduction))),
            Modifier.padding(top = 6.dp), fontFamily = AppFonts.mono, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
