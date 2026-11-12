package io.github.yyan115.opentune.ui

import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.yyan115.opentune.R
import io.github.yyan115.opentune.core.Track
import io.github.yyan115.opentune.core.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

/** The play and pause button: a white square. */
@Composable
fun PlayControl(playing: Boolean, buffering: Boolean = false, size: Dp = 72.dp, onClick: () -> Unit) {
    val description = stringResource(if (playing) R.string.player_pause else R.string.player_play)
    Box(Modifier.size(size).background(Color.White).clickable(onClick = onClick).semantics { contentDescription = description; role = Role.Button }, contentAlignment = Alignment.Center) {
        if (buffering) CircularProgressIndicator(Modifier.size(size * .35f), color = Color.Black, strokeWidth = 2.dp)
        else Icon(if (playing) OpenTuneIcons.PauseSolid else OpenTuneIcons.PlaySolid, null, Modifier.size(size * .42f), tint = Color.Black)
    }
}

/** The playback position in milliseconds. The activity provides the position of the player and a preview provides a fixed one. */
val LocalPlaybackPosition = staticCompositionLocalOf<StateFlow<Long>> { MutableStateFlow(0L) }

/** The mini player. A swipe to the left skips and a swipe to the right dismisses. */
@Composable
fun MiniPlayer(state: PlaybackState, position: State<Long>, onOpen: () -> Unit, onPlay: () -> Unit, onNext: () -> Unit, onDismiss: () -> Unit = {}) {
    val track = state.track ?: return
    var width by remember { mutableIntStateOf(1) }
    var offset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(if (dragging) offset else 0f, spring(stiffness = Spring.StiffnessMedium), label = "mini-player-swipe")
    val stop = stringResource(R.string.player_stop)
    Box(Modifier.testTag("mini-player").onSizeChanged { width = it.width.coerceAtLeast(1) }
        .graphicsLayer { translationX = shown; alpha = 1f - .7f * (shown.absoluteValue / width).coerceIn(0f, 1f) }
        .semantics { customActions = listOf(CustomAccessibilityAction(stop) { onDismiss(); true }) }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { dragging = true; offset = 0f },
                onDragEnd = { dragging = false; if (offset < -width * .3f) onNext() else if (offset > width * .3f) onDismiss(); offset = 0f },
                onDragCancel = { dragging = false; offset = 0f },
            ) { change, amount -> change.consume(); offset += amount }
        }) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackArtwork(track, null, Modifier.size(40.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 6.dp)) {
                    Text(track.title, fontFamily = AppFonts.body, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (state.error != null) stringResource(R.string.player_stopped) else track.artist, fontFamily = AppFonts.body, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onPlay) {
                    if (state.buffering) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 1.5.dp)
                    else Icon(if (state.playing) OpenTuneIcons.PauseSolid else OpenTuneIcons.PlaySolid, stringResource(if (state.playing) R.string.player_pause else R.string.player_play), Modifier.size(20.dp))
                }
                IconButton(onClick = onNext, enabled = state.nextIndex >= 0) { Icon(OpenTuneIcons.SkipNextSolid, stringResource(R.string.player_next), Modifier.size(20.dp)) }
            }
            // The line moves with the song. Reading the position here redraws only this line.
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = .12f))) {
                Box(Modifier.fillMaxHeight().graphicsLayer { transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, .5f); scaleX = if (state.duration > 0) (position.value.toFloat() / state.duration).coerceIn(0f, 1f) else 0f }
                    .fillMaxWidth().background(Color.White))
            }
        }
    }
}

/**
 * The full-screen player. The text and the controls always stay on screen, and these gestures work on the cover:
 * double tap saves the track, swipe sideways changes track,
 * swipe up opens the queue, swipe down closes the player, long press opens the track menu,
 * and a drag along the right edge changes the volume.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@UnstableApi
@Composable
fun NowPlayingScreen(
    state: AppState,
    vm: MainViewModel,
    onClose: () -> Unit,
    onQueue: () -> Unit,
    onMenu: (Track) -> Unit,
    onAlbum: (Track) -> Unit = {},
    onArtist: (Track) -> Unit = {},
    onCredits: (Track) -> Unit = {},
    initialLyrics: Boolean = false,
) {
    val track = state.playback.track ?: return
    val queue = state.playback.queue.ifEmpty { listOf(track) }
    val currentIndex = state.playback.index.coerceIn(0, queue.lastIndex)
    var lyrics by rememberSaveable { mutableStateOf(initialLyrics) }
    var timer by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf(false) }
    var storyShare by remember { mutableStateOf(false) }
    var spectrum by remember { mutableStateOf(false) }
    // The text and the controls always stay on screen.
    val chrome = true
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val playing = state.playback.playing
    val saved = state.favorites.any { it.id == track.id }

    fun poke() {}

    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume) }
    var hud by remember { mutableIntStateOf(0) }
    var hudVisible by remember { mutableStateOf(false) }
    LaunchedEffect(hud) { if (hud > 0) { hudVisible = true; delay(1200); hudVisible = false } }

    LaunchedEffect(track.id, lyrics) { if (lyrics) vm.loadLyrics() }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        LaunchedEffect(Unit) {
            withFrameNanos { }
            WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
        }
        Box(Modifier.fillMaxSize().background(Color(0xFF0A0A0A)).pointerInput(Unit) {
            // A swipe up opens the queue and a swipe down closes the player. Each counts once, when the finger lifts.
            var total = 0f
            detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                if (total < -110f) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onQueue() } else if (total > 150f) onClose()
            }, onDragCancel = { total = 0f }) { _, amount -> total += amount }
        }) {
            // The cover, blurred, gives the screen its colour.
            Crossfade(track.artwork, animationSpec = tween(400), label = "player-backdrop") { art ->
                AsyncImage(ImageRequest.Builder(context).data(art).crossfade(false).build(), null,
                    Modifier.fillMaxSize().blur(60.dp).graphicsLayer { alpha = .5f; scaleX = 1.4f; scaleY = 1.4f }, contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66000000), Color(0xAA000000), Color(0xF0090909)))))

            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val landscape = maxWidth > maxHeight && maxWidth >= 480.dp
                val scale = 1f

                // The cover, or the lyrics in its place. The caller decides how much room there is.
                val coverContent: @Composable (Dp) -> Unit = { coverSize ->
                        AnimatedContent(lyrics, Modifier.fillMaxSize(), transitionSpec = {
                            (fadeIn(tween(260)) + slideInVertically(spring(dampingRatio = .85f, stiffness = 420f)) { if (targetState) it / 8 else -it / 10 } + scaleIn(tween(260), if (targetState) .96f else 1.04f)) togetherWith
                                (fadeOut(tween(160)) + scaleOut(tween(200), if (targetState) 1.04f else .96f))
                        }, label = "cover-lyrics") { showLyrics ->
                            if (showLyrics) LyricsPanel(state, vm, Modifier.fillMaxSize().padding(horizontal = 24.dp).testTag("lyrics"))
                            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                val pager = rememberPagerState(initialPage = currentIndex) { queue.size }
                                LaunchedEffect(currentIndex) { if (pager.currentPage != currentIndex && currentIndex in queue.indices) pager.animateScrollToPage(currentIndex, animationSpec = spring(stiffness = 500f)) }
                                LaunchedEffect(pager.settledPage) { if (pager.settledPage != currentIndex && pager.settledPage in queue.indices) vm.jumpTo(pager.settledPage) }
                                HorizontalPager(pager, Modifier.size(coverSize + 24.dp).graphicsLayer { scaleX = scale; scaleY = scale }.testTag("cover-pager"), pageSpacing = 16.dp, beyondViewportPageCount = 1) { page ->
                                    val pageTrack = queue.getOrNull(page) ?: track
                                    val distance = ((pager.currentPage - page) + pager.currentPageOffsetFraction).absoluteValue
                                    var heart by remember { mutableIntStateOf(0) }
                                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = (1f - distance * .6f).coerceIn(.3f, 1f); val s = (1f - distance * .08f).coerceIn(.9f, 1f); scaleX = s; scaleY = s }
                                        .pointerInput(pageTrack.id) {
                                            detectTapGestures(
                                                onDoubleTap = { vm.favorite(pageTrack); heart++; haptic.performHapticFeedback(HapticFeedbackType.LongPress); poke() },
                                                onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onCredits(pageTrack) },
                                            )
                                        }, contentAlignment = Alignment.Center) {
                                        TrackArtwork(pageTrack, stringResource(R.string.player_artwork, pageTrack.album), Modifier.size(coverSize))
                                        HeartBurst(heart)
                                    }
                                }
                            }
                        }
                }

                // The panel under the cover. It is a part of the layout, so the cover takes exactly the room that it leaves,
                // and when the D'JAI strip appears or goes away the cover follows its height frame by frame.
                val panelContent: @Composable (Modifier) -> Unit = { panelModifier ->
                        AnimatedVisibility(chrome, panelModifier,
                        enter = fadeIn(tween(160)) + slideInVertically(spring(dampingRatio = .85f, stiffness = 420f)) { it / 3 }, exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 3 }) {
                        Column(Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = .9f, stiffness = 500f)).padding(horizontal = 24.dp).padding(bottom = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    AnimatedContent(track, contentKey = { it.id }, transitionSpec = { (slideInVertically(tween(220)) { it / 3 } + fadeIn(tween(200))) togetherWith (slideOutVertically(tween(160)) { -it / 3 } + fadeOut(tween(120))) }, label = "title") { shown ->
                                        Column {
                                            Text(shown.title, Modifier.testTag("player-title").then(if (shown.albumId.isNotBlank()) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.track_go_album)) { onAlbum(shown) } else Modifier),
                                                fontFamily = AppFonts.body, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(shown.artist, Modifier.testTag("player-artist").padding(top = 2.dp).then(if (shown.artistId.isNotBlank()) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.track_go_artist)) { onArtist(shown) } else Modifier),
                                                fontFamily = AppFonts.body, fontSize = 15.sp, color = Color.White.copy(alpha = .65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                                val pop by animateFloatAsState(if (saved) 1f else .9f, spring(.45f, 600f), label = "heart")
                                IconButton(onClick = { vm.favorite(track); poke() }, Modifier.graphicsLayer { scaleX = pop; scaleY = pop }) {
                                    Icon(if (saved) OpenTuneIcons.Favorite else OpenTuneIcons.FavoriteBorder, stringResource(if (saved) R.string.track_unsave else R.string.track_save), Modifier.size(26.dp), tint = if (saved) Color.White else Color.White.copy(alpha = .7f))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            SeekLine(vm, track.id, state.playback.duration) { poke() }
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                ToggleIcon(OpenTuneIcons.Shuffle, stringResource(R.string.player_shuffle), state.playback.shuffle) { vm.toggleShuffle(); poke() }
                                IconButton(onClick = { vm.previous(); poke() }, Modifier.size(52.dp)) { Icon(OpenTuneIcons.FastRewind, stringResource(R.string.player_previous), Modifier.size(30.dp), tint = Color.White) }
                                PlayControl(playing, state.playback.buffering, 60.dp) { vm.togglePlay(); poke() }
                                IconButton(onClick = { vm.next(); poke() }, Modifier.size(52.dp), enabled = state.playback.nextIndex >= 0) {
                                    Icon(OpenTuneIcons.FastForward, stringResource(R.string.player_next), Modifier.size(30.dp), tint = if (state.playback.nextIndex >= 0) Color.White else Color.White.copy(alpha = .3f))
                                }
                                ToggleIcon(if (state.playback.repeat == Player.REPEAT_MODE_ONE) OpenTuneIcons.RepeatOne else OpenTuneIcons.Repeat,
                                    stringResource(when (state.playback.repeat) { Player.REPEAT_MODE_ONE -> R.string.player_repeat_one; Player.REPEAT_MODE_ALL -> R.string.player_repeat_all; else -> R.string.player_repeat_off }),
                                    state.playback.repeat != Player.REPEAT_MODE_OFF) { vm.toggleRepeat(); poke() }
                            }
                            Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                val timerOn = state.sleepEndsAt > 0 || state.sleepAtTrackEnd
                                TextTool(OpenTuneIcons.Lyrics, stringResource(R.string.player_lyrics), lyrics) { lyrics = !lyrics; poke() }
                                TextTool(OpenTuneIcons.Timer, stringResource(R.string.player_timer_label), timerOn) { timer = true }
                                TextTool(OpenTuneIcons.QueueMusic, stringResource(R.string.queue_title), false) { onQueue() }
                                TextTool(OpenTuneIcons.Waves, stringResource(R.string.djai_name), state.settings.djai) { vm.settings { it.copy(djai = !it.djai) }; poke() }
                            }
                            if (state.settings.djai) DjStrip(Modifier.padding(bottom = 8.dp))
                            UpNext(state, vm, onQueue)
                        }
                    }
                }

                if (landscape) {
                    val coverSize = minOf(maxHeight - 32.dp, maxWidth * .42f).coerceAtLeast(160.dp)
                    Box(Modifier.align(Alignment.CenterStart).padding(start = 24.dp).size(coverSize), contentAlignment = Alignment.Center) { coverContent(coverSize) }
                    panelContent(Modifier.align(Alignment.CenterEnd).fillMaxWidth(.5f))
                } else Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(56.dp))
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        coverContent(minOf(maxWidth - 56.dp, maxHeight - 24.dp).coerceIn(96.dp, 720.dp))
                    }
                    panelContent(Modifier.fillMaxWidth())
                }

                // Top: the quality badge, which opens the signal path, with the share and menu actions.
                AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = fadeIn(tween(140)) + slideInVertically(tween(180)) { -it / 2 }, exit = fadeOut(tween(160)) + slideOutVertically(tween(180)) { -it / 2 }) {
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Both sides are 144 dp wide, so the badge sits in the exact middle.
                        Box(Modifier.width(144.dp)) { IconButton(onClick = onClose) { Icon(OpenTuneIcons.KeyboardArrowDown, stringResource(R.string.player_close), Modifier.size(26.dp), tint = Color.White) } }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { QualityBadge(state.playback.quality, onClick = { path = true; poke() }) }
                        Row(Modifier.width(144.dp), horizontalArrangement = Arrangement.End) {
                            IconButton(onClick = { spectrum = true }) { Icon(OpenTuneIcons.GraphicEq, stringResource(R.string.path_fullscreen), Modifier.size(21.dp), tint = Color.White) }
                            IconButton(onClick = { storyShare = true }) { Icon(OpenTuneIcons.Share, stringResource(R.string.share_story_button), Modifier.size(21.dp), tint = Color.White) }
                            IconButton(onClick = { onMenu(track) }) { Icon(OpenTuneIcons.MoreVert, stringResource(R.string.player_track_options), Modifier.size(21.dp), tint = Color.White) }
                        }
                    }
                }


                // The right edge changes the volume: a drag up raises it, a drag down lowers it.
                Box(Modifier.align(Alignment.CenterEnd).width(44.dp).fillMaxHeight(.7f).pointerInput(Unit) {
                    detectVerticalDragGestures { change, amount ->
                        change.consume()
                        volume = (volume - amount / 520f).coerceIn(0f, 1f)
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * maxVolume).toInt(), 0)
                        hud++
                    }
                })
                AnimatedVisibility(hudVisible, Modifier.align(Alignment.CenterEnd).padding(end = 14.dp), enter = fadeIn(tween(100)), exit = fadeOut(tween(300))) { VolumeBar(volume) }
            }
        }
    }

    if (path) SignalPathSheet(state.playback.quality, track, playing, onSpectrum = { path = false; spectrum = true },
        elzar = state.settings.takeIf { it.elzar }?.let { Triple(it.elzarGlue, it.elzarTape, it.elzarVinyl) }) { path = false }
    if (spectrum) SpectrumScreen(state) { spectrum = false }
    if (timer) SleepTimerSheet(state, vm) { timer = false }
    if (storyShare) StoryShareSheet(track, state.playback.quality, vm::storyDetails) { storyShare = false }
}

/** A rectangular heart that jumps in at the double tap and fades. */
@Composable
private fun HeartBurst(trigger: Int) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(trigger) { if (trigger > 0) { visible = true; delay(650); visible = false } }
    AnimatedVisibility(visible, enter = scaleIn(spring(.4f, 700f), .3f) + fadeIn(tween(80)), exit = fadeOut(tween(300)) + scaleOut(tween(300), 1.3f)) {
        Icon(OpenTuneIcons.Favorite, stringResource(R.string.gesture_double_tap_liked), Modifier.size(96.dp), tint = Color.White)
    }
}

@Composable
private fun VolumeBar(volume: Float) {
    Column(Modifier.width(40.dp).height(180.dp).background(Color(0xE6101010)).border(1.dp, Color.White.copy(alpha = .2f), RectangleShape).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(if (volume <= .01f) OpenTuneIcons.VolumeOff else if (volume < .5f) OpenTuneIcons.VolumeDown else OpenTuneIcons.VolumeUp, stringResource(R.string.volume_label), Modifier.size(18.dp), tint = Color.White)
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).width(4.dp).background(Color.White.copy(alpha = .25f)), contentAlignment = Alignment.BottomCenter) { Box(Modifier.fillMaxWidth().fillMaxHeight(volume.coerceIn(0f, 1f)).background(Color.White)) }
    }
}

@Composable
private fun ToggleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, on: Boolean, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(44.dp)) { Icon(icon, description, Modifier.size(20.dp), tint = if (on) Color.White else Color.White.copy(alpha = .4f)) }
}

/** A small tool with an icon and a label. */
@Composable
private fun TextTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.clickable(role = Role.Button, onClick = onClick).border(1.dp, if (on) Color.White else Color.White.copy(alpha = .2f), RectangleShape).background(if (on) Color.White.copy(alpha = .12f) else Color.Transparent).padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(15.dp), tint = if (on) Color.White else Color.White.copy(alpha = .7f))
        Spacer(Modifier.width(6.dp))
        Text(label, fontFamily = AppFonts.body, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (on) Color.White else Color.White.copy(alpha = .7f), maxLines = 1)
    }
}

/** The next track, in a slim strip. When the track changes the strip slides up and a new one replaces it. */
@UnstableApi
@Composable
private fun UpNext(state: AppState, vm: MainViewModel, onQueue: () -> Unit) {
    val next = state.playback.queue.getOrNull(state.playback.nextIndex)
    Box(Modifier.fillMaxWidth().height(56.dp).background(Color.White.copy(alpha = .08f)).testTag("up-next").clipToBounds()) {
        AnimatedContent(next, Modifier.fillMaxSize(), transitionSpec = {
            (slideInVertically(spring(dampingRatio = .8f, stiffness = 450f)) { it } + fadeIn(tween(180))) togetherWith (slideOutVertically(tween(180)) { -it } + fadeOut(tween(140)))
        }, label = "up-next") { item ->
            if (item == null) Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.queue_playing_next), fontFamily = AppFonts.body, fontSize = 12.sp, color = Color.White.copy(alpha = .5f))
            } else Row(Modifier.fillMaxSize().clickable(onClick = onQueue).padding(start = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackArtwork(item, null, Modifier.size(40.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.player_up_next).uppercase(), fontFamily = AppFonts.body, fontSize = 9.sp, letterSpacing = 1.4.sp, color = Color.White.copy(alpha = .5f))
                    Text(item.title, fontFamily = AppFonts.body, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                }
                Row(Modifier.clickable(role = Role.Button) { vm.next() }.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.player_skip), fontFamily = AppFonts.body, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    Spacer(Modifier.width(6.dp)); Icon(OpenTuneIcons.SkipNextSolid, null, Modifier.size(14.dp), tint = Color.White)
                }
            }
        }
    }
}

/** The seek line with the elapsed and total time. It reads the position here, so only this part redraws as the music plays. */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
private fun SeekLine(vm: MainViewModel, trackId: String, duration: Long, onTouch: () -> Unit) {
    val position by LocalPlaybackPosition.current.collectAsStateWithLifecycle()
    var seeking by remember(trackId) { mutableStateOf<Float?>(null) }
    val colors = SliderDefaults.colors(activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .25f), thumbColor = Color.White)
    Slider(seeking ?: position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()), { seeking = it; onTouch() }, Modifier.fillMaxWidth().height(32.dp),
        enabled = duration > 0, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), onValueChangeFinished = { seeking?.let { vm.seek(it.toLong()) }; seeking = null },
        thumb = { Box(Modifier.size(10.dp, 14.dp).background(Color.White)) },
        track = { SliderDefaults.Track(it, Modifier.height(2.dp), colors = colors, thumbTrackGapSize = 0.dp, drawStopIndicator = null, trackInsideCornerSize = 0.dp) }, colors = colors)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatDuration(seeking?.toLong() ?: position), fontFamily = AppFonts.mono, fontSize = 11.sp, color = Color.White.copy(alpha = .7f))
        Text(formatDuration(duration), fontFamily = AppFonts.mono, fontSize = 11.sp, color = Color.White.copy(alpha = .7f))
    }
}
