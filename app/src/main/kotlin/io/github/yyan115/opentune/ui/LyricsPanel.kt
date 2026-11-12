package io.github.yyan115.opentune.ui

import io.github.yyan115.opentune.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import io.github.yyan115.opentune.core.Lyrics

/** How long automatic scrolling waits after the listener drags the lyrics. */
private const val MANUAL_SCROLL_PAUSE_MS = 4_000L

/** Lyrics that follow the song. Lines with timing highlight and scroll as the track plays, and a tap on a line seeks to it. */
@UnstableApi
@Composable
fun LyricsPanel(state: AppState, vm: MainViewModel, modifier: Modifier = Modifier) {
    Box(modifier.testTag("lyrics-panel"), contentAlignment = Alignment.Center) {
        val lyrics = state.lyrics
        when {
            state.lyricsStatus == LyricsStatus.Loading || state.lyricsStatus == LyricsStatus.Idle -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 1.5.dp)
            state.lyricsStatus == LyricsStatus.Ready && lyrics != null -> if (lyrics.synced) SyncedLyrics(lyrics, vm::seek) else PlainLyrics(lyrics)
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(when (state.lyricsStatus) {
                    LyricsStatus.Offline -> stringResource(R.string.lyrics_offline)
                    LyricsStatus.Error -> stringResource(R.string.lyrics_error)
                    else -> stringResource(R.string.lyrics_none)
                }, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.lyricsStatus == LyricsStatus.Error) TextButton(onClick = vm::retryLyrics) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics, onSeek: (Long) -> Unit) {
    val position = LocalPlaybackPosition.current.collectAsStateWithLifecycle()
    // The view changes only when the line changes, not each time the position does.
    val current by remember(lyrics) { derivedStateOf { lyrics.lineAt(position.value + LEAD_MS) } }
    val listState = rememberLazyListState()
    var pausedUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) pausedUntil = System.currentTimeMillis() + MANUAL_SCROLL_PAUSE_MS }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val offset = with(androidx.compose.ui.platform.LocalDensity.current) { -(maxHeight * 0.3f).roundToPx() }
        LaunchedEffect(current) {
            if (current >= 0 && System.currentTimeMillis() >= pausedUntil) listState.animateScrollToItem(current, offset)
            // Nothing to wait for: the scroll is short and the spring on the lines gives the motion.
        }
        CompositionLocalProvider(LocalLayoutDirection provides if (lyrics.rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = maxHeight * 0.3f, bottom = maxHeight * 0.6f, start = 8.dp, end = 8.dp)) {
                itemsIndexed(lyrics.lines, key = { index, line -> "$index:${line.timeMs}" }) { index, line ->
                    val active = index == current
                    // Lines fade with their distance from the line that sings, and the active line springs up to full size.
                    val distance = if (current < 0) 3 else kotlin.math.abs(index - current)
                    val alpha by animateFloatAsState(if (active) 1f else (.55f - distance * .1f).coerceAtLeast(.2f), tween(220), label = "line-alpha")
                    val scale by animateFloatAsState(if (active) 1f else .84f, spring(dampingRatio = .7f, stiffness = 500f), label = "line-scale")
                    val lift by animateFloatAsState(if (active) 0f else 6f, spring(stiffness = 500f), label = "line-lift")
                    Text(line.text.ifBlank { "♪" }, Modifier.fillMaxWidth().clickable { onSeek(line.timeMs) }.padding(vertical = 9.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale; translationY = lift; this.alpha = alpha; transformOrigin = TransformOrigin(if (lyrics.rightToLeft) 1f else 0f, .5f) },
                        style = AppFonts.title(28, 34), color = MaterialTheme.colorScheme.onSurface)
                }
                item(key = "credit") { Attribution(lyrics) }
            }
        }
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics) {
    CompositionLocalProvider(LocalLayoutDirection provides if (lyrics.rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(lyrics.plain, style = MaterialTheme.typography.bodyLarge, lineHeight = 28.sp)
            Attribution(lyrics)
        }
    }
}

@Composable
private fun Attribution(lyrics: Lyrics) {
    if (lyrics.provider.isNotBlank()) Text(stringResource(R.string.lyrics_credit, lyrics.provider.lowercase().replaceFirstChar(Char::uppercase)), Modifier.padding(top = 18.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Lines switch slightly before their timestamp so the highlight is not behind the singer. */
private const val LEAD_MS = 250L
