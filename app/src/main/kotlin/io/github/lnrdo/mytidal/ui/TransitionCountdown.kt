package io.github.lnrdo.mytidal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lnrdo.mytidal.R
import io.github.lnrdo.mytidal.playback.dj.DjMix
import io.github.lnrdo.mytidal.playback.dj.DjPhase
import kotlinx.coroutines.delay

/** The countdown shows when a transition is this close, and stays while it runs. */
private const val SHOW_WITHIN_MS = 60_000L

/**
 * A large countdown in the lower right corner of the cover: the time until D'JAI starts the transition, and while it runs, the time until the next song has
 * taken over. The engine reports a few times a second, and the countdown runs on the clock between reports so that it moves smoothly.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun TransitionCountdown(modifier: Modifier = Modifier) {
    val ui by DjMix.ui.collectAsStateWithLifecycle()
    // When the last report came, and when the transition began.
    val reportedAt = remember(ui.startsInMs, ui.phase) { System.currentTimeMillis() }
    var mixingSince by remember { mutableLongStateOf(0L) }
    LaunchedEffect(ui.phase) { if (ui.phase == DjPhase.MIXING) mixingSince = System.currentTimeMillis() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(100) } }
    val planned = ui.phase == DjPhase.PLANNED && ui.startsInMs > 0
    val mixing = ui.phase == DjPhase.MIXING && ui.lengthMs > 0
    val left = when {
        planned -> (ui.startsInMs - (now - reportedAt)).coerceAtLeast(0L)
        mixing -> (ui.lengthMs - (now - mixingSince)).coerceAtLeast(0L)
        else -> 0L
    }
    val visible = (planned && left <= SHOW_WITHIN_MS) || mixing
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(250)) + scaleIn(tween(250), .85f), exit = fadeOut(tween(250)) + scaleOut(tween(250), .9f)) {
        Column(Modifier.padding(10.dp).background(Color.Black.copy(alpha = .62f)).padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) {
            Text(stringResource(if (mixing) R.string.countdown_mixing else R.string.countdown_mix_in).uppercase(), fontFamily = AppFonts.body, fontSize = 10.sp, letterSpacing = 1.6.sp,
                color = Color.White.copy(alpha = .75f))
            val seconds = (left + 999) / 1000
            Text("%d:%02d".format(seconds / 60, seconds % 60), fontFamily = AppFonts.mono, fontSize = 34.sp, fontWeight = FontWeight.Medium, color = Color.White)
            ui.nextTitle?.takeIf { planned }?.let {
                Text(it, fontFamily = AppFonts.body, fontSize = 11.sp, color = Color.White.copy(alpha = .75f), maxLines = 1, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}
