package io.github.lnrdo.mytidal.ui

import io.github.lnrdo.mytidal.problemText
import io.github.lnrdo.mytidal.R
import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.util.Consumer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import io.github.lnrdo.mytidal.OpenTuneApp
import io.github.lnrdo.mytidal.core.Release
import io.github.lnrdo.mytidal.core.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Whether this activity can show a picture-in-picture window. */
fun Activity.enterVideoPip() {
    if (Build.VERSION.SDK_INT >= 26) runCatching {
        enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
    }
}

/**
 * A music video over the whole screen. It is drawn inside the activity, not in a dialog,
 * so that it also fills the picture-in-picture window. Music pauses through Android's audio focus.
 */
@UnstableApi
@Composable
fun VideoPlayerOverlay(release: Release, offline: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = context.applicationContext as OpenTuneApp
    val activity = remember(context) { context.findActivity() }
    var error by remember(release.id) { mutableStateOf<String?>(null) }
    var playing by remember(release.id) { mutableStateOf(false) }
    var position by remember(release.id) { mutableLongStateOf(0) }
    var duration by remember(release.id) { mutableLongStateOf(0) }
    var controls by remember(release.id) { mutableStateOf(true) }
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    val pauseLabel = stringResource(R.string.video_pause)
    val playLabel = stringResource(R.string.video_play)
    val player = remember(release.id) {
        ExoPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true).build()
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(failure: PlaybackException) { error = resources.getString(R.string.video_error_play) }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    DisposableEffect(activity) {
        val listener = Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    LaunchedEffect(release.id) {
        if (offline) { error = resources.getString(R.string.video_error_offline); return@LaunchedEffect }
        try {
            val url = app.tidal.videoUrl(release.id)
            // The player must be used on the main thread, whichever thread the network call resumed on.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.playWhenReady = true
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            android.util.Log.w("OpenTune", "The video request failed: ${failure.javaClass.simpleName}")
            error = if (failure is io.github.lnrdo.mytidal.core.ServiceException) context.problemText(failure) else resources.getString(R.string.video_error_load)
        }
    }
    LaunchedEffect(player) {
        while (true) { position = player.currentPosition.coerceAtLeast(0); duration = player.duration.takeIf { it > 0 } ?: 0; delay(500) }
    }
    LaunchedEffect(controls, playing, inPip) { if (controls && playing && !inPip) { delay(3_500); controls = false } }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("video-player").semantics { stateDescription = "position=$position playing=$playing" }.clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { controls = !controls }) {
        ContentFrame(player, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (!playing && error == null && position == 0L) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        error?.let { Text(it, Modifier.align(Alignment.Center).padding(32.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium) }
        if (controls && !inPip) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000)).safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(OpenTuneIcons.Close, stringResource(R.string.video_close), tint = Color.White) }
                    Text(listOf(release.title, release.artist).filter(String::isNotBlank).joinToString(" · "), Modifier.weight(1f), color = Color.White,
                        fontFamily = AppFonts.body, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (Build.VERSION.SDK_INT >= 26 && activity != null) IconButton(onClick = { activity.enterVideoPip() }) { Icon(OpenTuneIcons.Fullscreen, stringResource(R.string.video_pip), tint = Color.White) }
                }
                IconButton(onClick = { if (playing) player.pause() else player.play() }, modifier = Modifier.align(Alignment.Center).size(72.dp)
                    .semantics { contentDescription = if (playing) pauseLabel else playLabel }) {
                    Icon(if (playing) OpenTuneIcons.Pause else OpenTuneIcons.PlayArrow, null, Modifier.size(44.dp), tint = Color.White)
                }
                Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Slider(position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()), { player.seekTo(it.toLong()); position = it.toLong() },
                        valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = duration > 0)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatDuration(position), color = Color.White, fontSize = 11.sp)
                        Text(formatDuration(duration), color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}
