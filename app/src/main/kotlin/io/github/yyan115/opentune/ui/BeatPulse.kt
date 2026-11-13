package io.github.yyan115.opentune.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import io.github.yyan115.opentune.core.dj.Camelot
import kotlin.math.floor

/** Whether the tempo that is shown blinks at its beats per minute. The activity provides it from the settings. */
val LocalDjPulse = staticCompositionLocalOf { true }

/**
 * The clock that the blinking tempos read: the time of the current frame in nanoseconds. It runs only while something blinks.
 *
 * It is not an animation of Compose on purpose. Those are stretched by the animation scale of the system (a developer option, and battery saver can stop
 * them), and a tempo that blinks at the wrong speed is worse than one that does not blink. The frame time is the real time.
 */
private object BeatClock {
    val now = mutableLongStateOf(0L)
    var users by mutableIntStateOf(0)
}

/** Runs the clock of the blinking tempos. It is placed once, at the root of the screen. */
@Composable
fun BeatClockHost() {
    val active = BeatClock.users > 0
    LaunchedEffect(active) { if (active) while (true) withFrameNanos { BeatClock.now.longValue = it } }
}

/** How bright a blink is at [phase] of its beat (0 at the beat, 1 just before the next): full at the beat, fading fast and then slowly. */
private fun brightness(phase: Double): Float {
    val p = (phase - floor(phase)).toFloat()
    val fall = 1f - (1f - p) * (1f - p)
    return 1f - 0.7f * fall
}

/**
 * Makes what it is put on blink at [bpm]: full at each beat, fading until the next. It is a drawing effect: it redraws only the thing that blinks, and it
 * recomposes nothing. [phase], when it returns a value, says where the music is in its beat (from 0 to 1), so that the blink falls on the beats that are
 * heard. Without it the blink keeps the tempo on the clock, and all the tempos that are alike blink together.
 */
@Composable
fun Modifier.beatPulse(bpm: Double?, enabled: Boolean = LocalDjPulse.current, phase: (() -> Double?)? = null): Modifier {
    if (!enabled || bpm == null || bpm < 40.0 || bpm > 240.0) return this
    DisposableEffect(Unit) { BeatClock.users++; onDispose { BeatClock.users-- } }
    val period = 60_000_000_000.0 / bpm
    return graphicsLayer {
        val now = BeatClock.now.longValue
        alpha = brightness(phase?.invoke() ?: (now / period))
    }
}

/** A Camelot code with its key in musical notation after it: "8A Am". A code that has no name is shown as it is. */
fun keyWithNote(code: String): String = Camelot.noteName(code)?.let { "$code $it" } ?: code
