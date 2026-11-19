package io.github.lnrdo.mytidal.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lnrdo.mytidal.R
import kotlin.math.abs

/** The ways that the lyrics are shown while a song plays. [id] is what the settings keep. */
enum class LyricStyle(val id: String, @StringRes val nameRes: Int, @StringRes val hintRes: Int) {
    Classic("classic", R.string.lyric_classic, R.string.lyric_classic_text),
    Mist("mist", R.string.lyric_mist, R.string.lyric_mist_text),
    Typewriter("typewriter", R.string.lyric_typewriter, R.string.lyric_typewriter_text),
    Karaoke("karaoke", R.string.lyric_karaoke, R.string.lyric_karaoke_text),
    Words("words", R.string.lyric_words, R.string.lyric_words_text),
    Glow("glow", R.string.lyric_glow, R.string.lyric_glow_text),
    Focus("focus", R.string.lyric_focus, R.string.lyric_focus_text);

    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } ?: Classic }
}

/** The playback position, moved on at every frame between the updates of the player, so that a line can be written or lit smoothly. */
@Composable
fun rememberSmoothPosition(): State<Long> {
    val raw = LocalPlaybackPosition.current.collectAsStateWithLifecycle()
    val out = remember { mutableLongStateOf(raw.value) }
    LaunchedEffect(Unit) {
        var last = raw.value; var at = System.nanoTime()
        while (true) androidx.compose.runtime.withFrameNanos {
            val value = raw.value
            if (value != last) { last = value; at = System.nanoTime() }
            // The player reports twice a second: go on a little between its reports, and not beyond when it is paused.
            out.longValue = value + ((System.nanoTime() - at) / 1_000_000).coerceAtMost(600)
        }
    }
    return out
}

private val Progressive = setOf(LyricStyle.Typewriter, LyricStyle.Karaoke, LyricStyle.Words)

/** One line of the lyrics in [style]. [index] is its place, and [current] the place of the line that sings (-1 before the first). */
@Composable
fun LyricLineView(style: LyricStyle, text: String, startMs: Long, endMs: Long, index: Int, current: Int, rtl: Boolean, position: State<Long>, onClick: () -> Unit) {
    val active = index == current
    val past = current >= 0 && index < current
    val d = if (current < 0) 3 else abs(index - current)
    val misty = style == LyricStyle.Mist
    val alphaTarget = when (style) {
        LyricStyle.Classic -> if (active) 1f else (.55f - d * .1f).coerceAtLeast(.2f)
        LyricStyle.Mist -> if (active) 1f else if (past) (.5f - d * .25f).coerceAtLeast(0f) else (.45f - d * .12f).coerceAtLeast(.08f)
        LyricStyle.Typewriter -> if (active) 1f else if (past) (.5f - d * .08f).coerceAtLeast(.2f) else 0f
        LyricStyle.Karaoke, LyricStyle.Words -> if (active) 1f else if (past) .55f else .4f
        LyricStyle.Glow -> if (active) 1f else (.4f - d * .08f).coerceAtLeast(.15f)
        LyricStyle.Focus -> if (active) 1f else if (d == 1) .15f else 0f
    }
    val blurTarget = if (misty && !active) (if (past) 4f + d * 4f else 3f + d * 2f).coerceAtMost(14f) else 0f
    val liftTarget = when { active -> 0f; misty && past -> -d * 10f; style == LyricStyle.Classic || misty -> 6f; else -> 0f }
    val scaleTarget = if (active) 1f else when (style) { LyricStyle.Typewriter, LyricStyle.Karaoke, LyricStyle.Words -> .92f; LyricStyle.Focus -> .8f; else -> .84f }
    val alpha by animateFloatAsState(alphaTarget, if (misty) tween(800) else tween(220), label = "line-alpha")
    val scale by animateFloatAsState(scaleTarget, if (misty) tween(800) else spring(dampingRatio = .7f, stiffness = 500f), label = "line-scale")
    val lift by animateFloatAsState(liftTarget, if (misty) tween(800) else spring(stiffness = 500f), label = "line-lift")
    val blur by animateDpAsState(blurTarget.dp, tween(if (misty) 800 else 200), label = "line-blur")
    val base = MaterialTheme.colorScheme.onSurface
    val content = text.ifBlank { "♪" }
    // The part of the line that is already sung, from 0 to 1. Only the line that sings reads the position, and so only it redraws at each frame.
    val progress = if (active && style in Progressive) ((position.value - startMs).toFloat() / (endMs - startMs).coerceAtLeast(1500L)).coerceIn(0f, 1f) else if (past) 1f else 0f
    val shown: AnnotatedString = when (style) {
        LyricStyle.Typewriter -> {
            val n = (progress * 1.6f * content.length).toInt().coerceIn(0, content.length)
            buildAnnotatedString { append(content.take(n)); withStyle(SpanStyle(color = Color.Transparent)) { append(content.drop(n)) } }
        }
        LyricStyle.Karaoke -> {
            val n = (progress * 1.15f * content.length).toInt().coerceIn(0, content.length)
            buildAnnotatedString { append(content.take(n)); withStyle(SpanStyle(color = base.copy(alpha = .35f))) { append(content.drop(n)) } }
        }
        LyricStyle.Words -> {
            val words = content.split(' ')
            buildAnnotatedString {
                words.forEachIndexed { i, word ->
                    val seen = if (active) (progress * 1.4f * words.size - i).coerceIn(.12f, 1f) else if (past) 1f else .12f
                    withStyle(SpanStyle(color = base.copy(alpha = seen))) { append(word) }
                    if (i < words.lastIndex) append(' ')
                }
            }
        }
        else -> AnnotatedString(content)
    }
    val size = if (style == LyricStyle.Focus) 40 else 34
    var textStyle = AppFonts.title(size, size + 8)
    if (style == LyricStyle.Glow && active) textStyle = textStyle.copy(shadow = Shadow(MaterialTheme.colorScheme.primary.copy(alpha = .9f), blurRadius = 28f))
    if (style == LyricStyle.Focus) textStyle = textStyle.copy(textAlign = TextAlign.Center)
    Text(shown, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp)
        .then(if (blur > .1.dp) Modifier.blur(blur, BlurredEdgeTreatment.Unbounded) else Modifier)
        .graphicsLayer { scaleX = scale; scaleY = scale; translationY = lift; this.alpha = alpha; transformOrigin = TransformOrigin(if (style == LyricStyle.Focus) .5f else if (rtl) 1f else 0f, .5f) },
        style = textStyle, color = base)
}
