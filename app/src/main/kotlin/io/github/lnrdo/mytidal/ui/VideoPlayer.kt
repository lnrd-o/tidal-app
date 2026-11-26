package io.github.lnrdo.mytidal.ui

import io.github.lnrdo.mytidal.problemText
import io.github.lnrdo.mytidal.R
import androidx.compose.ui.res.stringResource
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
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

/** One resolution of a video, as the stream offers it: its height, its bit rate, and where it is among the tracks. */
class VideoVariant(val height: Int, val bitrate: Int, val group: Tracks.Group, val index: Int) {
    val label: String get() = "${height}p"
}

/** The resolutions of the video that plays, highest first; each height once, at its best bit rate. */
private fun variantsOf(tracks: Tracks): List<VideoVariant> = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
    (0 until group.length).filter { group.isTrackSupported(it) }.map { i -> val f = group.getTrackFormat(i); VideoVariant(f.height, f.bitrate, group, i) }
}.filter { it.height > 0 }.groupBy { it.height }.map { (_, same) -> same.maxBy { it.bitrate } }.sortedByDescending { it.height }

/** The quality that the listener asked for: "auto", or a height in pixels such as "1080". Above what the video has, its highest is used. */
private fun ExoPlayer.applyQuality(choice: String, variants: List<VideoVariant>) {
    val builder = trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
    val height = choice.toIntOrNull()
    if (height != null && variants.isNotEmpty()) {
        val pick = variants.firstOrNull { it.height <= height } ?: variants.last()
        builder.setOverrideForType(TrackSelectionOverride(pick.group.mediaTrackGroup, pick.index))
    }
    trackSelectionParameters = builder.build()
}

/**
 * A music video over the whole screen. It is drawn inside the activity, not in a dialog, so that it also fills the picture-in-picture window. Music pauses
 * through Android's audio focus. The screen stays on and the system bars hide while it plays; a tap shows the controls, which fade after a few seconds.
 * A double tap on the left or the right goes back or forward ten seconds. The quality is chosen from the resolutions that the stream offers (or left to
 * adapt to the connection), and the choice is remembered for the next video. The picture can fit the screen or fill it, and the screen can be turned.
 */
@UnstableApi
@Composable
fun VideoPlayerOverlay(release: Release, offline: Boolean, quality: String, onQuality: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val view = LocalView.current
    val app = context.applicationContext as OpenTuneApp
    val activity = remember(context) { context.findActivity() }
    var error by remember(release.id) { mutableStateOf<String?>(null) }
    var attempt by remember(release.id) { mutableIntStateOf(0) }
    var playing by remember(release.id) { mutableStateOf(false) }
    var buffering by remember(release.id) { mutableStateOf(true) }
    var position by remember(release.id) { mutableLongStateOf(0) }
    var buffered by remember(release.id) { mutableLongStateOf(0) }
    var duration by remember(release.id) { mutableLongStateOf(0) }
    var seeking by remember(release.id) { mutableStateOf<Float?>(null) }
    var controls by remember(release.id) { mutableStateOf(true) }
    var fill by remember { mutableStateOf(false) }
    var variants by remember(release.id) { mutableStateOf(emptyList<VideoVariant>()) }
    var playingHeight by remember(release.id) { mutableIntStateOf(0) }
    var qualityMenu by remember { mutableStateOf(false) }
    var skip by remember { mutableIntStateOf(0) } // -1 back, +1 forward, for the flash on a double tap
    var skipCount by remember { mutableIntStateOf(0) }
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    val pauseLabel = stringResource(R.string.video_pause)
    val playLabel = stringResource(R.string.video_play)
    val player = remember(release.id) {
        ExoPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true).setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
    }
    val currentQuality by rememberUpdatedState(quality)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE }
            override fun onPlayerError(failure: PlaybackException) { error = resources.getString(R.string.video_error_play) }
            override fun onTracksChanged(tracks: Tracks) {
                val found = variantsOf(tracks)
                // The choice is applied once, when the resolutions are first known.
                if (variants.isEmpty() && found.isNotEmpty()) player.applyQuality(currentQuality, found)
                variants = found
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { playingHeight = videoSize.height }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    // While the video is open the screen stays on and the system bars step aside; the orientation that the app had comes back when it closes.
    DisposableEffect(activity) {
        val window = activity?.window
        val insets = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        val orientation = activity?.requestedOrientation
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
            if (orientation != null) activity.requestedOrientation = orientation
        }
    }
    DisposableEffect(activity) {
        val listener = Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    LaunchedEffect(release.id, attempt) {
        error = null
        if (offline) { error = resources.getString(R.string.video_error_offline); return@LaunchedEffect }
        try {
            val url = app.tidal.videoUrl(release.id)
            // The player must be used on the main thread, whichever thread the network call resumed on. TIDAL sends an HLS playlist of several
            // resolutions; it is named as one so that the player reads all of them.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                val item = MediaItem.Builder().setUri(url).apply { if (url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8) }.build()
                player.setMediaItem(item); player.prepare(); player.playWhenReady = true
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            android.util.Log.w("OpenTune", "The video request failed: ${failure.javaClass.simpleName}")
            error = if (failure is io.github.lnrdo.mytidal.core.ServiceException) context.problemText(failure) else resources.getString(R.string.video_error_load)
        }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0); buffered = player.bufferedPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it > 0 } ?: 0
            delay(250)
        }
    }
    LaunchedEffect(controls, playing, inPip, qualityMenu, seeking) { if (controls && playing && !inPip && !qualityMenu && seeking == null) { delay(3_500); controls = false } }
    LaunchedEffect(skipCount) { if (skipCount > 0) { delay(600); skip = 0 } }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black).testTag("video-player").semantics { stateDescription = "position=$position playing=$playing" }
        .pointerInput(Unit) {
            detectTapGestures(onTap = { controls = !controls }, onDoubleTap = { at ->
                val forward = at.x > size.width / 2
                if (forward) player.seekForward() else player.seekBack()
                skip = if (forward) 1 else -1; skipCount++
            })
        }) {
        ContentFrame(player, Modifier.fillMaxSize(), contentScale = if (fill) ContentScale.Crop else ContentScale.Fit)
        if (buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(44.dp), color = Color.White, strokeWidth = 2.5.dp)
        // The flash of a double tap: back or forward ten seconds, on its side of the screen.
        AnimatedVisibility(skip != 0, Modifier.align(if (skip > 0) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 48.dp), enter = fadeIn(tween(80)), exit = fadeOut(tween(300))) {
            Column(Modifier.clip(CircleShape).background(Color.White.copy(alpha = .16f)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (skip > 0) OpenTuneIcons.FastForward else OpenTuneIcons.FastRewind, null, Modifier.size(30.dp), tint = Color.White)
                Text(stringResource(R.string.video_ten_seconds), color = Color.White, fontSize = 12.sp)
            }
        }
        error?.let { message ->
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                if (!offline) TextButton({ attempt++ }) { Text(stringResource(R.string.action_retry), color = Color.White) }
            }
        }
        AnimatedVisibility(controls && !inPip, Modifier.fillMaxSize(), enter = fadeIn(tween(160)), exit = fadeOut(tween(260))) {
            Box(Modifier.fillMaxSize()) {
                // Shades at the top and at the foot, so that the controls read on any picture without darkening all of it.
                Box(Modifier.fillMaxWidth().height(140.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .7f), Color.Transparent))))
                Box(Modifier.fillMaxWidth().height(180.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .75f)))))
                Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onClose) { Icon(OpenTuneIcons.KeyboardArrowDown, stringResource(R.string.video_close), Modifier.size(28.dp), tint = Color.White) }
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(release.title, color = Color.White, fontFamily = AppFonts.body, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (release.artist.isNotBlank()) Text(release.artist, color = Color.White.copy(alpha = .7f), fontFamily = AppFonts.body, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // The quality: what plays now, and a tap opens the choice.
                        val now = if (playingHeight > 0) "${playingHeight}p" else "—"
                        Text(if (quality == "auto") stringResource(R.string.video_quality_auto_now, now) else now,
                            Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = .16f)).clickable(role = Role.Button) { qualityMenu = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp).semantics { contentDescription = resources.getString(R.string.video_quality) },
                            color = Color.White, fontFamily = AppFonts.body, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        IconButton(onClick = { fill = !fill }) { Icon(OpenTuneIcons.AspectRatio, stringResource(if (fill) R.string.video_fit else R.string.video_fill), tint = Color.White) }
                        if (activity != null) IconButton(onClick = {
                            activity.requestedOrientation = if (activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }) { Icon(OpenTuneIcons.ScreenRotation, stringResource(R.string.video_rotate), tint = Color.White) }
                        if (Build.VERSION.SDK_INT >= 26 && activity != null) IconButton(onClick = { activity.enterVideoPip() }) { Icon(OpenTuneIcons.Fullscreen, stringResource(R.string.video_pip), tint = Color.White) }
                    }
                    Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundControl(OpenTuneIcons.FastRewind, stringResource(R.string.video_back), 56.dp) { player.seekBack() }
                        RoundControl(if (playing) OpenTuneIcons.PauseSolid else OpenTuneIcons.PlaySolid, if (playing) pauseLabel else playLabel, 76.dp) { if (playing) player.pause() else player.play() }
                        RoundControl(OpenTuneIcons.FastForward, stringResource(R.string.video_forward), 56.dp) { player.seekForward() }
                    }
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 20.dp, vertical = 12.dp)) {
                        VideoSeek(seeking ?: position.toFloat(), buffered, duration, { seeking = it }) { seeking?.let { player.seekTo(it.toLong()); position = it.toLong() }; seeking = null }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(formatDuration(seeking?.toLong() ?: position), color = Color.White, fontFamily = AppFonts.mono, fontSize = 12.sp)
                            Text("-" + formatDuration((duration - (seeking?.toLong() ?: position)).coerceAtLeast(0)), color = Color.White.copy(alpha = .75f), fontFamily = AppFonts.mono, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
    if (qualityMenu) QualitySheet(quality, variants, playingHeight, { qualityMenu = false }) { choice ->
        onQuality(choice); player.applyQuality(choice, variants); qualityMenu = false
    }
}

@Composable
private fun RoundControl(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = .35f)).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * .46f), tint = Color.White)
    }
}

/** A seek line with what is already loaded drawn under the position, and a thumb that grows under the finger. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoSeek(value: Float, buffered: Long, duration: Long, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val max = duration.coerceAtLeast(1).toFloat()
    val loaded = (buffered / max).coerceIn(0f, 1f)
    Slider(value.coerceIn(0f, max), onChange, Modifier.fillMaxWidth().height(28.dp), enabled = duration > 0, valueRange = 0f..max, onValueChangeFinished = onDone,
        thumb = { Box(Modifier.size(14.dp).clip(CircleShape).background(Color.White)) },
        track = { s ->
            val played = ((s.value - s.valueRange.start) / (s.valueRange.endInclusive - s.valueRange.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .22f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(loaded).background(Color.White.copy(alpha = .35f)))
                Box(Modifier.fillMaxHeight().fillMaxWidth(played).background(Color.White))
            }
        })
}

/** The choice of quality: automatic, which follows the connection, or one of the resolutions of this video, with its bit rate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualitySheet(choice: String, variants: List<VideoVariant>, playingHeight: Int, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismiss, shape = SheetShape, containerColor = Color(0xFF161616), contentColor = Color.White, dragHandle = { SheetHandle() }) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.video_quality), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium, color = Color.White)
            QualityRow(stringResource(R.string.video_quality_auto), stringResource(R.string.video_quality_auto_text), choice == "auto") { onPick("auto") }
            if (variants.isEmpty()) Text(stringResource(R.string.video_quality_loading), Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = Color.White.copy(alpha = .6f), fontSize = 13.sp)
            for (v in variants) {
                val detail = (if (v.bitrate > 0) "%.1f Mbps".format(v.bitrate / 1_000_000f) else "") + if (v.height == playingHeight) "  ·  " + stringResource(R.string.video_quality_playing) else ""
                QualityRow(qualityName(v.height), detail.trim(' ', '·'), choice == v.height.toString()) { onPick(v.height.toString()) }
            }
        }
    }
}

@Composable
private fun qualityName(height: Int): String = when {
    height >= 2160 -> stringResource(R.string.video_quality_4k, height)
    height >= 1080 -> stringResource(R.string.video_quality_full_hd, height)
    height >= 720 -> stringResource(R.string.video_quality_hd, height)
    else -> stringResource(R.string.video_quality_sd, height)
}

@Composable
private fun QualityRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            if (detail.isNotBlank()) Text(detail, color = Color.White.copy(alpha = .6f), fontSize = 12.sp)
        }
        if (selected) Icon(OpenTuneIcons.Check, null, Modifier.size(20.dp), tint = Color.White)
    }
}
