package io.github.yyan115.opentune.ui

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.yyan115.opentune.R

/**
 * The spectrum analyzer on the whole screen, in portrait and in landscape. Portrait gives tall columns with the track above them.
 * Landscape gives a wide, dense analyzer with the track at its side. The screen stays on while it is open, and a swipe down or the back button closes it.
 */
@Composable
fun SpectrumScreen(state: AppState, onClose: () -> Unit) {
    val track = state.playback.track
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        LaunchedEffect(Unit) {
            withFrameNanos { }
            WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
        }
        DisposableEffect(window) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().testTag("spectrum-screen").pointerInput(Unit) {
            var total = 0f
            detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total > 150f) onClose() }) { _, amount -> total += amount }
        }) {
            val landscape = maxWidth > maxHeight
            if (landscape) Row(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.width(210.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Header(state, track?.title, track?.artist, onClose)
                }
                SpectrumAnalyzer(state.playback.playing, Modifier.weight(1f).fillMaxHeight(), columns = 96, rows = 26, fill = true, showScale = true)
            } else Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Header(state, track?.title, track?.artist, onClose)
                Spacer(Modifier.height(24.dp))
                SpectrumAnalyzer(state.playback.playing, Modifier.weight(1f).fillMaxWidth(), columns = 40, rows = 44, fill = true, showScale = true)
            }
        }
    }
}

@Composable
private fun Header(state: AppState, title: String?, artist: String?, onClose: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, Modifier.size(40.dp)) { Icon(OpenTuneIcons.Close, stringResource(R.string.action_close), Modifier.size(22.dp), tint = Color.White) }
            Spacer(Modifier.width(8.dp))
            QualityBadge(state.playback.quality, onClick = {})
        }
        Spacer(Modifier.height(16.dp))
        if (title != null) Text(title, fontFamily = AppFonts.body, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (artist != null) Text(artist, Modifier.padding(top = 4.dp), fontFamily = AppFonts.body, fontSize = 14.sp, color = Color.White.copy(alpha = .6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
