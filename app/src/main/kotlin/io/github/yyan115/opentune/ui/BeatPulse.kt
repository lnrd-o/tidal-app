package io.github.yyan115.opentune.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import io.github.yyan115.opentune.core.dj.Camelot

/** Whether the tempo that is shown blinks at its beats per minute. The activity provides it from the settings. */
val LocalDjPulse = staticCompositionLocalOf { true }

/**
 * Makes what it is put on blink at [bpm]: it is full at each beat and fades until the next. It is a drawing effect, so it does not recompose
 * anything while it runs, and it does nothing when [enabled] is false or the tempo is not a tempo.
 */
@Composable
fun Modifier.beatPulse(bpm: Double?, enabled: Boolean = LocalDjPulse.current): Modifier {
    if (!enabled || bpm == null || bpm < 40.0 || bpm > 240.0) return this
    val transition = rememberInfiniteTransition(label = "beat")
    val level by transition.animateFloat(1f, 0.3f, infiniteRepeatable(tween((60_000.0 / bpm).toInt(), easing = FastOutSlowInEasing), RepeatMode.Restart), label = "level")
    return graphicsLayer { alpha = level }
}

/** A Camelot code with its key in musical notation after it: "8A Am". A code that has no name is shown as it is. */
fun keyWithNote(code: String): String = Camelot.noteName(code)?.let { "$code $it" } ?: code
