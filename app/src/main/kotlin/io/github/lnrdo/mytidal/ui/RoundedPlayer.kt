package io.github.lnrdo.mytidal.ui

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.interaction.collectIsPressedAsState
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import io.github.lnrdo.mytidal.R
import io.github.lnrdo.mytidal.core.Track
import io.github.lnrdo.mytidal.core.dj.Cue
import io.github.lnrdo.mytidal.core.formatDuration
import io.github.lnrdo.mytidal.playback.dj.DjMix
import io.github.lnrdo.mytidal.playback.dj.DjPhase

/** The colour of what is on, and the deep colour under everything: both come from the cover through the scheme of the app. */
private val Active: Color @Composable get() = MaterialTheme.colorScheme.primary
private val Base: Color @Composable get() = MaterialTheme.colorScheme.background
/** The ink of the player: the text colour of the scheme, nearly white in dark and nearly black in light. */
private val Ink: Color @Composable get() = MaterialTheme.colorScheme.onBackground

/**
 * The rounded player. The cover runs from edge to edge at the top and melts into a blur of itself, the title sits on it with two round glass
 * buttons, and the line under the title says what matters now: D'JAI's next mix when one is near, else the line that is sung, else the next song.
 * The seek line is round and thick, with the quality in its middle, and three tools sit at the bottom: the lyrics, D'JAI and the queue, with the output under them.
 * Lyrics take the place of the cover, with a small header. The gestures are those of the first design: swipe down closes, swipe up opens the queue, swipe
 * sideways on the cover changes the song, double tap saves it and a long press shows its credits.
 */
@UnstableApi
@Composable
fun NowPlayingRounded(
    state: AppState,
    vm: MainViewModel,
    onClose: () -> Unit,
    onQueue: () -> Unit,
    onMenu: (Track) -> Unit,
    onAlbum: (Track) -> Unit,
    onArtist: (Track) -> Unit,
    onCredits: (Track) -> Unit,
    initialLyrics: Boolean,
) {
    val track = state.playback.track ?: return
    // Only the player takes its colours from the cover, in light and in dark; the rest of the app stays plain. The sheets that it opens share them.
    val seed = rememberCoverSeed(track.artwork)
    val dark = LocalAppTheme.current.dark
    val scheme = remember(seed, dark) { coverScheme(seed, dark) }
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        RoundedPlayerBody(state, vm, onClose, onQueue, onMenu, onAlbum, onArtist, onCredits, initialLyrics)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
private fun RoundedPlayerBody(
    state: AppState,
    vm: MainViewModel,
    onClose: () -> Unit,
    onQueue: () -> Unit,
    onMenu: (Track) -> Unit,
    onAlbum: (Track) -> Unit,
    onArtist: (Track) -> Unit,
    onCredits: (Track) -> Unit,
    initialLyrics: Boolean,
) {
    val track = state.playback.track ?: return
    var lyrics by rememberSaveable { mutableStateOf(initialLyrics) }
    var timer by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf(false) }
    var storyShare by remember { mutableStateOf(false) }
    var spectrum by remember { mutableStateOf(false) }
    var djSheet by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var stemsOpen by remember { mutableStateOf(false) }
    var spatialOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val saved = state.favorites.any { it.id == track.id }
    val accent = rememberArtworkAccent(track.artwork, state.settings.adaptColor)
    val cueRevision by vm.app.library.cueChanges.collectAsStateWithLifecycle()
    var cues by remember(track.id) { mutableStateOf(emptyList<Cue>()) }
    LaunchedEffect(track.id, cueRevision) { cues = vm.cuesOf(track.id) }
    // The line under the title reads the lyrics, so they are loaded for every song and not only when they are open.
    LaunchedEffect(track.id) { vm.loadLyrics() }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        val darkTheme = LocalAppTheme.current.dark
        LaunchedEffect(darkTheme) {
            withFrameNanos { }
            WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !darkTheme; isAppearanceLightNavigationBars = !darkTheme }
        }
        Box(Modifier.fillMaxSize().appEffect().background(Base).testTag("player-rounded").pointerInput(Unit) {
            var total = 0f
            detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                if (total < -110f) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onQueue() } else if (total > 150f) onClose()
            }, onDragCancel = { total = 0f }) { _, amount -> total += amount }
        }) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val landscape = maxWidth > maxHeight && maxWidth >= 480.dp
                val roomy = maxHeight >= 420.dp
                // A tablet: its shorter side is at least 600 dp. It frames the cover in the middle instead of running it from edge to edge.
                val tablet = minOf(maxWidth, maxHeight) >= 600.dp
                // The sizes follow the screen: by its height (a short phone is tighter) and by its shorter side (a tablet draws larger), and a wide screen
                // keeps the controls to a comfortable measure in its middle.
                val scale = minOf((maxHeight / 820.dp).coerceIn(.88f, 1.25f), (minOf(maxWidth, maxHeight) / 412.dp).coerceIn(.9f, 1.25f))
                val side = (maxWidth * .07f).coerceIn(20.dp, 56.dp)
                val measure = if (tablet) 560.dp else 620.dp
                // The controls take about this much height; the cover takes what is left, up to a square as wide as the screen.
                val controlsHeight = 380.dp * scale
                val coverHeight = when {
                    landscape -> 0.dp
                    tablet -> minOf(maxWidth * .72f, maxHeight - controlsHeight - 160.dp).coerceAtLeast(240.dp)
                    else -> minOf(maxWidth, (maxHeight - controlsHeight).coerceAtLeast(maxHeight * .34f))
                }
                val backdrop = rememberBackdrop()
                // Layer one, the backdrop: the cover blurred far (decoded small, which the blur hides, so it costs little), the
                // shade that keeps the text readable and, in portrait, the sharp cover. The panes of layer two blur what lies under it.
                Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
                    AsyncImage(ImageRequest.Builder(context).data(track.artwork).size(160).crossfade(700).build(), null,
                        Modifier.fillMaxSize().blur(70.dp).graphicsLayer { alpha = .9f; scaleX = 1.5f; scaleY = 1.5f }, contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Base.copy(alpha = .10f), Base.copy(alpha = .45f), Base.copy(alpha = .86f)))))
                    if (state.settings.adaptColor) Box(Modifier.fillMaxSize().background(accent.copy(alpha = .10f)))
                    if (!landscape && !lyrics && !tablet) Cover(state, vm, track, Modifier.fillMaxWidth().height(coverHeight).align(Alignment.TopCenter), fade = true, onCredits = onCredits)
                }
                // Layer two, the controls and their blurred panes.
                CompositionLocalProvider(LocalBackdrop provides backdrop, LocalPlayerScale provides scale) {
                    val title: @Composable () -> Unit = { TitleRow(track, saved, { vm.favorite(track) }, { menu = true }, onAlbum, onArtist) }
                    val now: @Composable () -> Unit = { NowLine(state, { lyrics = true }, { djSheet = true }, onQueue) }
                    val seek: @Composable () -> Unit = { RoundSeek(vm, track.id, state.playback.duration, state.playback.quality, cues, accent) { path = true } }
                    val transport: @Composable () -> Unit = { Transport(state, vm) }
                    val tools: @Composable () -> Unit = { Tools(state, lyrics, { lyrics = !lyrics }, { djSheet = true }, onQueue) { path = true } }
                    if (landscape) Row(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = side, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(side),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true).clip(RoundedCornerShape(22.dp))) {
                            if (lyrics) LyricsPanel(state, vm, Modifier.fillMaxSize().padding(8.dp))
                            else Cover(state, vm, track, Modifier.fillMaxSize(), fade = false, onCredits = onCredits)
                        }
                        // With room the parts spread evenly over the height; a short screen lets them scroll.
                        Column(Modifier.weight(1f).widthIn(max = measure).fillMaxHeight().then(if (roomy) Modifier else Modifier.verticalScroll(rememberScrollState())),
                            verticalArrangement = if (roomy) Arrangement.SpaceEvenly else Arrangement.spacedBy(14.dp)) {
                            title(); now(); seek(); transport(); tools()
                        }
                    } else if (lyrics) Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = side), horizontalAlignment = Alignment.CenterHorizontally) {
                        Handle(Modifier)
                        Row(Modifier.widthIn(max = measure).fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            TrackArtwork(track, null, Modifier.size(56.dp * scale).clip(RoundedCornerShape(12.dp)).clickable { lyrics = false })
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                TitleText(track, 19.sp * scale, onAlbum)
                                Text(track.artist, fontFamily = AppFonts.body, fontSize = 15.sp * scale, color = Ink.copy(alpha = .55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            GlassCircle(if (saved) OpenTuneIcons.Favorite else OpenTuneIcons.FavoriteBorder, stringResource(if (saved) R.string.track_unsave else R.string.track_save), 44.dp * scale) { vm.favorite(track) }
                            Spacer(Modifier.width(10.dp))
                            GlassCircle(OpenTuneIcons.MoreHoriz, stringResource(R.string.player_track_options), 44.dp * scale) { menu = true }
                        }
                        LyricsPanel(state, vm, Modifier.weight(1f).widthIn(max = measure).fillMaxWidth().testTag("lyrics"))
                        Column(Modifier.widthIn(max = measure).fillMaxWidth()) {
                            seek(); Spacer(Modifier.height(16.dp * scale)); transport(); Spacer(Modifier.height(16.dp * scale)); tools()
                        }
                        Spacer(Modifier.height(8.dp))
                    } else {
                        Handle(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 6.dp))
                        // Reach: what the thumb uses most (the controls, the seek line and the tools) sits low, in the part of the screen that one hand
                        // reaches; the height that is left goes above the title, between it and the cover, not between the controls.
                        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = side).padding(bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (tablet) {
                                // A tablet frames the cover in the middle, rounded, with air around it.
                                Spacer(Modifier.statusBarsPadding().height(36.dp))
                                Box(Modifier.size(coverHeight).clip(RoundedCornerShape(28.dp))) { Cover(state, vm, track, Modifier.fillMaxSize(), fade = false, onCredits = onCredits) }
                                Gap(16.dp, 1f)
                            } else {
                                Spacer(Modifier.height(coverHeight * .80f))
                                Gap(0.dp, 1f)
                            }
                            QualityLine(state.playback.quality, Modifier) { path = true }
                            Spacer(Modifier.height(14.dp * scale))
                            Column(Modifier.widthIn(max = measure).fillMaxWidth()) {
                                title()
                                Spacer(Modifier.height(12.dp * scale))
                                now()
                            }
                            Gap(18.dp * scale, .25f)
                            Box(Modifier.widthIn(max = measure).fillMaxWidth()) { Column { seek() } }
                            Gap(14.dp * scale, .2f)
                            Box(Modifier.widthIn(max = measure).fillMaxWidth()) { transport() }
                            Gap(16.dp * scale, .2f)
                            Box(Modifier.widthIn(max = measure).fillMaxWidth()) { tools() }
                        }
                    }
                }
            }
        }
        if (menu) GlassMenu(track, saved, lyrics, onDismiss = { menu = false }, onLike = { vm.favorite(track) }, onSpectrum = { spectrum = true }, onShare = { storyShare = true },
            onTimer = { timer = true }, onPath = { path = true }, onStems = { stemsOpen = true }, stemsOn = state.settings.stemsOn, onSpatial = { spatialOpen = true }, onDj = { djSheet = true }, onLyrics = { lyrics = !lyrics }, onQueue = onQueue,
            onAlbum = { onAlbum(track) }, onArtist = { onArtist(track) }, onMore = { onMenu(track) })
    }

    if (path) SignalPathSheet(state.playback.quality, track, state.playback.playing, accent = accent, onSpectrum = { path = false; spectrum = true },
        elzar = state.settings.takeIf { it.elzar }?.let { Triple(it.elzarGlue, it.elzarTape, it.elzarVinyl) }) { path = false }
    if (djSheet) DjaiSheet(state.settings.djai, state.settings.djNeural, track.id, cues, state.playback.duration, { value -> vm.settings { it.copy(djai = value) } }) { djSheet = false }
    if (spectrum) SpectrumScreen(state, accent, onStyle = { next -> vm.settings { it.copy(spectrumStyle = next.id) } }) { spectrum = false }
    if (stemsOpen) StemsSheet(track, state, vm) { stemsOpen = false }
    if (spatialOpen) SpatialSheet(state, vm) { spatialOpen = false }
    if (timer) SleepTimerSheet(state, vm) { timer = false }
    if (storyShare) StoryShareSheet(track, state.playback.quality, vm::storyDetails) { storyShare = false }
}

/** How much larger or smaller than a common phone the controls are drawn, from the height of the screen. */
private val LocalPlayerScale = androidx.compose.runtime.compositionLocalOf { 1f }

/** A gap of at least [floor] that also takes [share] of the height left over. */
@Composable
private fun ColumnScope.Gap(floor: Dp, share: Float) {
    Spacer(Modifier.height(floor))
    Spacer(Modifier.weight(share))
}

/** The cover, with its gestures and with D'JAI's countdown in its corner. With [fade] its lower part melts into the screen. */
@UnstableApi
@Composable
private fun Cover(state: AppState, vm: MainViewModel, track: Track, modifier: Modifier, fade: Boolean, onCredits: (Track) -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var heart by remember { mutableIntStateOf(0) }
    // The cover follows the finger, read only where it is drawn: a drag moves pixels and composes nothing.
    val shift = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val hasNext = state.playback.nextIndex >= 0
    Box(modifier.pointerInput(track.id) {
        detectTapGestures(onDoubleTap = { vm.favorite(track); heart++; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
            onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onCredits(track) })
    }.pointerInput(track.id, hasNext) {
        // A swipe sideways changes the song: to the left the next one, to the right the one before. The cover springs back either way.
        detectHorizontalDragGestures(onDragEnd = {
            val moved = shift.value
            if (moved < -140f && hasNext) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.next() }
            else if (moved > 140f) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.previous() }
            scope.launch { shift.animateTo(0f, spring(dampingRatio = .72f, stiffness = 380f)) }
        }, onDragCancel = { scope.launch { shift.animateTo(0f, spring(dampingRatio = .72f, stiffness = 380f)) } }) { change, amount ->
            change.consume(); scope.launch { shift.snapTo(shift.value + amount) }
        }
    }) {
        AnimatedContent(track.artwork, Modifier.fillMaxSize(), transitionSpec = {
            (fadeIn(tween(420)) + androidx.compose.animation.scaleIn(tween(420), initialScale = 1.04f)) togetherWith fadeOut(tween(300))
        }, label = "rounded-cover") { art ->
            AsyncImage(ImageRequest.Builder(context).data(art).crossfade(false).build(), stringResource(R.string.player_artwork, track.album),
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = shift.value * .35f
                    rotationZ = shift.value / 220f
                    if (fade) compositingStrategy = CompositingStrategy.Offscreen
                }.then(if (fade) Modifier.drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0f to Color.Black, .55f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                } else Modifier), contentScale = ContentScale.Crop)
        }
        Box(Modifier.align(Alignment.Center)) { HeartBurstRound(heart) }
        Box(Modifier.align(Alignment.BottomEnd).padding(bottom = if (fade) 96.dp else 0.dp)) { TransitionCountdown() }
        if (state.settings.djLoadOverlay) Box(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 24.dp)) { LoadOverlay() }
    }
}

@Composable
private fun HeartBurstRound(trigger: Int) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(trigger) { if (trigger > 0) { visible = true; kotlinx.coroutines.delay(650); visible = false } }
    val scale by animateFloatAsState(if (visible) 1f else .4f, spring(.45f, 600f), label = "rounded-heart")
    if (visible || scale > .45f) Icon(OpenTuneIcons.Favorite, stringResource(R.string.gesture_double_tap_liked), Modifier.size(96.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (visible) 1f else 0f }, tint = Ink)
}

@Composable
private fun Handle(modifier: Modifier) {
    Box(modifier.width(40.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Ink.copy(alpha = .6f)))
}

/** The source in plain words over the foot of the cover: codec, bits, rate, bit rate and channels. A tap opens the signal path. */
@Composable
private fun QualityLine(quality: String, modifier: Modifier, onClick: () -> Unit) {
    val source = SourceReadout.from(quality)
    if (source.codec.isBlank()) return
    val text = listOfNotNull(source.codec, source.bits?.let { "$it-bit" }, source.sampleRate?.let { "$it kHz" }, source.bitrate?.let { "$it kbps" }).joinToString(" · ")
    Text(text, modifier.blurPane(CircleShape, fill = .1f).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp),
        fontFamily = AppFonts.body, fontSize = 11.sp, color = Ink.copy(alpha = .8f))
}


@Composable
private fun TitleText(track: Track, size: androidx.compose.ui.unit.TextUnit, onAlbum: (Track) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (track.explicit) {
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(Ink.copy(alpha = .6f)), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.rounded_explicit), fontFamily = AppFonts.body, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Base)
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(track.title, Modifier.testTag("player-title").then(if (track.albumId.isNotBlank()) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.track_go_album)) { onAlbum(track) } else Modifier),
            fontFamily = AppFonts.body, fontSize = size, fontWeight = FontWeight.Bold, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TitleRow(track: Track, saved: Boolean, onSave: () -> Unit, onMore: () -> Unit, onAlbum: (Track) -> Unit, onArtist: (Track) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // A new song slides its title in from below while the old one leaves upward.
        AnimatedContent(track, Modifier.weight(1f), contentKey = { it.id }, transitionSpec = {
            (androidx.compose.animation.slideInVertically(spring(dampingRatio = .85f, stiffness = 420f)) { it / 3 } + fadeIn(tween(220))) togetherWith
                (androidx.compose.animation.slideOutVertically(tween(180)) { -it / 3 } + fadeOut(tween(140)))
        }, label = "rounded-title") { shown ->
            Column {
                TitleText(shown, 22.sp * LocalPlayerScale.current, onAlbum)
                Text(shown.artist, Modifier.testTag("player-artist").then(if (shown.artistId.isNotBlank()) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.track_go_artist)) { onArtist(shown) } else Modifier),
                    fontFamily = AppFonts.body, fontSize = 17.sp * LocalPlayerScale.current, color = Ink.copy(alpha = .5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val pop by animateFloatAsState(if (saved) 1f else .92f, spring(.45f, 600f), label = "rounded-save")
        Box(Modifier.graphicsLayer { scaleX = pop; scaleY = pop }) {
            GlassCircle(if (saved) OpenTuneIcons.Favorite else OpenTuneIcons.FavoriteBorder, stringResource(if (saved) R.string.track_unsave else R.string.track_save), 42.dp * LocalPlayerScale.current, onSave)
        }
        Spacer(Modifier.width(10.dp))
        GlassCircle(OpenTuneIcons.MoreHoriz, stringResource(R.string.player_track_options), 42.dp * LocalPlayerScale.current, onMore)
    }
}

@Composable
private fun GlassCircle(icon: ImageVector, description: String, size: Dp, onClick: () -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val squeeze by animateFloatAsState(if (pressed) .9f else 1f, spring(dampingRatio = .5f, stiffness = 700f), label = "glass-press")
    Box(Modifier.size(size).graphicsLayer { scaleX = squeeze; scaleY = squeeze }.blurPane(CircleShape, fill = .35f)
        .clickable(press, androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * .44f), tint = Ink)
    }
}

/**
 * The one line under the title. A mix that comes within a minute, or runs, is said first, in D'JAI's gold; else the line that is sung now, which opens the
 * lyrics; else the next song, which opens the queue.
 */
@UnstableApi
@Composable
private fun NowLine(state: AppState, onLyrics: () -> Unit, onDj: () -> Unit, onQueue: () -> Unit) {
    // Only what is shown is read: the line that is sung changes a few times a minute and the countdown once a second, so the line is composed again only
    // then, not on every report of the position or of D'JAI.
    val djState = DjMix.ui.collectAsStateWithLifecycle()
    val positionState = LocalPlaybackPosition.current.collectAsStateWithLifecycle()
    val lyrics = state.lyrics.takeIf { state.lyricsStatus == LyricsStatus.Ready && state.lyricsTrackId == state.playback.track?.id && it?.synced == true }
    val lineIndex by remember(lyrics) { derivedStateOf { lyrics?.lineAt(positionState.value + 250) ?: -1 } }
    val line = lyrics?.lines?.getOrNull(lineIndex)?.text?.trim()?.ifBlank { null }
    val djai = state.settings.djai
    val djPhase by remember(djai) { derivedStateOf { if (!djai) DjPhase.OFF else djState.value.phase } }
    val djSeconds by remember { derivedStateOf { (djState.value.startsInMs + 999) / 1000 } }
    val djNext by remember { derivedStateOf { djState.value.nextTitle } }
    val next = state.playback.queue.getOrNull(state.playback.nextIndex)
    val mixSoon = djPhase == DjPhase.PLANNED && djSeconds in 1..60
    val mixing = djPhase == DjPhase.MIXING
    val (text, kind) = when {
        mixing -> (djNext?.let { stringResource(R.string.rounded_dj_mixing, it) } ?: stringResource(R.string.rounded_dj_mixing_plain)) to 2
        mixSoon -> (djNext?.let { stringResource(R.string.rounded_dj_mix_in, formatDuration(djSeconds * 1000), it) } ?: stringResource(R.string.rounded_dj_mix_in_plain, formatDuration(djSeconds * 1000))) to 2
        line != null -> line to 1
        next != null -> stringResource(R.string.rounded_up_next, next.title) to 3
        else -> "" to 0
    }
    Row(Modifier.fillMaxWidth().height(24.dp).clickable(enabled = kind != 0, role = Role.Button) { when (kind) { 1 -> onLyrics(); 2 -> onDj(); 3 -> onQueue() } },
        verticalAlignment = Alignment.CenterVertically) {
        if (kind == 2) {
            Icon(OpenTuneIcons.Djai, null, Modifier.size(16.dp), tint = DjaiGold)
            Spacer(Modifier.width(8.dp))
        }
        AnimatedContent(text, Modifier.weight(1f, fill = false), transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "rounded-now-line") { shown ->
            Text(shown, fontFamily = AppFonts.body, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (kind == 2) Color(0xFFE9D7A6) else Ink.copy(alpha = .78f))
        }
        if (kind != 0) {
            Spacer(Modifier.width(8.dp))
            Icon(OpenTuneIcons.ChevronRight, null, Modifier.size(18.dp), tint = Ink.copy(alpha = .55f))
        }
    }
}

/** A thick, round seek line with the phases of the song above it, the time on both sides and the quality in the middle. */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
private fun RoundSeek(vm: MainViewModel, trackId: String, duration: Long, quality: String, cues: List<Cue>, accent: Color, onQuality: () -> Unit) {
    val position by LocalPlaybackPosition.current.collectAsStateWithLifecycle()
    val buffered by LocalBufferedPosition.current.collectAsStateWithLifecycle()
    var seeking by remember(trackId) { mutableStateOf<Float?>(null) }
    val max = duration.coerceAtLeast(1).toFloat()
    val value = seeking ?: position.toFloat().coerceIn(0f, max)
    val grow = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(seeking != null) { grow.animateTo(if (seeking != null) 1f else 0f, spring(dampingRatio = .6f, stiffness = 500f)) }
    Box(Modifier.fillMaxWidth().height(28.dp)) {
        Slider(value, { seeking = it }, Modifier.fillMaxWidth().height(28.dp), enabled = duration > 0, valueRange = 0f..max,
            onValueChangeFinished = { seeking?.let { vm.seek(it.toLong()) }; seeking = null },
            // The line swells a little under the finger, with a spring; the fill is drawn, so it moves without layout.
            thumb = { Box(Modifier.size(16.dp).graphicsLayer { val s = grow.value; scaleX = .72f + .28f * s; scaleY = .72f + .28f * s }.clip(CircleShape).background(Ink)) },
            track = { slider ->
                val fraction = ((slider.value - slider.valueRange.start) / (slider.valueRange.endInclusive - slider.valueRange.start)).coerceIn(0f, 1f)
                val ink = Ink
                Box(Modifier.fillMaxWidth().height(8.dp).graphicsLayer { scaleY = .75f + .25f * grow.value }.clip(RoundedCornerShape(4.dp)).blurPane(RoundedCornerShape(4.dp), fill = .2f)
                    .drawBehind {
                        // What is loaded, fainter, under what is played.
                        drawRect(ink.copy(alpha = .30f), size = androidx.compose.ui.geometry.Size(size.width * (buffered / max).coerceIn(0f, 1f), size.height))
                        drawRect(ink.copy(alpha = .92f), size = androidx.compose.ui.geometry.Size(size.width * fraction, size.height))
                    })
            }, colors = SliderDefaults.colors(thumbColor = Ink))
        if (cues.isNotEmpty() && duration > 0) PhaseRibbon(cues, duration, position, accent, Modifier.matchParentSize())
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(formatDuration(seeking?.toLong() ?: position), Modifier.weight(1f), fontFamily = AppFonts.body, fontSize = 12.sp, color = Ink.copy(alpha = .6f))
        val tier = qualityTier(quality)
        val label = when (tier) {
            QualityTier.MAX -> stringResource(R.string.rounded_hires)
            QualityTier.HIFI -> stringResource(R.string.rounded_lossless)
            else -> SourceReadout.from(quality).codec.ifBlank { null }
        }
        if (label != null) Row(Modifier.clickable(role = Role.Button, onClick = onQuality).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (tier == QualityTier.MAX || tier == QualityTier.HIFI) { Icon(OpenTuneIcons.GraphicEq, null, Modifier.size(14.dp), tint = Ink.copy(alpha = .6f)); Spacer(Modifier.width(5.dp)) }
            Text(label, fontFamily = AppFonts.body, fontSize = 12.sp, color = Ink.copy(alpha = .6f))
        }
        val left = ((duration - (seeking?.toLong() ?: position)).coerceAtLeast(0))
        Text("-" + formatDuration(left), Modifier.weight(1f), fontFamily = AppFonts.body, fontSize = 12.sp, color = Ink.copy(alpha = .6f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

/** Shuffle, back, play, forward and repeat. The three in the middle are large and plain white, without a frame. */
@UnstableApi
@Composable
private fun Transport(state: AppState, vm: MainViewModel) {
    val playback = state.playback
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        SmallToggle(OpenTuneIcons.Shuffle, stringResource(R.string.player_shuffle), playback.shuffle) { vm.toggleShuffle() }
        val k = LocalPlayerScale.current
        PlainButton(OpenTuneIcons.FastRewind, stringResource(R.string.player_previous), 36.dp * k) { vm.previous() }
        // The play button is an orb of glass that gives under the finger; play and pause turn into each other.
        val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val pressed by press.collectIsPressedAsState()
        val squeeze by animateFloatAsState(if (pressed) .9f else 1f, spring(dampingRatio = .45f, stiffness = 650f), label = "play-press")
        Box(Modifier.size(68.dp * k).graphicsLayer { scaleX = squeeze; scaleY = squeeze }.blurPane(CircleShape, fill = .45f)
            .clickable(press, androidx.compose.material3.ripple(), role = Role.Button) { vm.togglePlay() }, contentAlignment = Alignment.Center) {
            if (playback.buffering) CircularProgressIndicator(Modifier.size(34.dp), color = Ink, strokeWidth = 2.dp)
            else AnimatedContent(playback.playing, transitionSpec = {
                (fadeIn(tween(160)) + androidx.compose.animation.scaleIn(spring(dampingRatio = .55f, stiffness = 600f), initialScale = .6f)) togetherWith
                    (fadeOut(tween(120)) + androidx.compose.animation.scaleOut(tween(120), targetScale = .6f))
            }, label = "play-pause") { playing ->
                Icon(if (playing) OpenTuneIcons.PauseSolid else OpenTuneIcons.PlaySolid, stringResource(if (playing) R.string.player_pause else R.string.player_play), Modifier.size(32.dp * k), tint = Ink)
            }
        }
        PlainButton(OpenTuneIcons.FastForward, stringResource(R.string.player_next), 36.dp * k, enabled = playback.nextIndex >= 0) { vm.next() }
        SmallToggle(if (playback.repeat == Player.REPEAT_MODE_ONE) OpenTuneIcons.RepeatOne else OpenTuneIcons.Repeat,
            stringResource(when (playback.repeat) { Player.REPEAT_MODE_ONE -> R.string.player_repeat_one; Player.REPEAT_MODE_ALL -> R.string.player_repeat_all; else -> R.string.player_repeat_off }),
            playback.repeat != Player.REPEAT_MODE_OFF) { vm.toggleRepeat() }
    }
}

@Composable
private fun PlainButton(icon: ImageVector, description: String, size: Dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.size(size + 14.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size), tint = if (enabled) Ink.copy(alpha = .95f) else Ink.copy(alpha = .3f))
    }
}

@Composable
private fun SmallToggle(icon: ImageVector, description: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(38.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (on) Active else Ink.copy(alpha = .4f))
    }
}

/** The three tools at the foot: lyrics, D'JAI with the square that says where its model runs, and the queue. The output of the sound is named under them. */
@UnstableApi
@Composable
private fun Tools(state: AppState, lyrics: Boolean, onLyrics: () -> Unit, onDj: () -> Unit, onQueue: () -> Unit, onOutput: () -> Unit) {
    val marker = rhythmMarker(state.settings.djai && state.settings.djNeural)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ToolButton(OpenTuneIcons.Lyrics, stringResource(R.string.player_lyrics), lyrics, onLyrics)
            Box {
                ToolButton(OpenTuneIcons.Djai, stringResource(R.string.djai_name), state.settings.djai, onDj, tint = null) { DjaiMark(24.dp * LocalPlayerScale.current, live = state.settings.djai, playing = state.playback.playing, tint = if (state.settings.djai) Active else Ink.copy(alpha = .75f)) }
                if (marker != null) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(7.dp).clip(CircleShape).background(marker.first).semantics { contentDescription = marker.second })
            }
            ToolButton(OpenTuneIcons.QueueMusic, stringResource(R.string.queue_title), false, onQueue)
        }
        val output = rememberOutputName()
        Text(output, Modifier.padding(top = 8.dp).clickable(role = Role.Button, onClick = onOutput).padding(horizontal = 8.dp, vertical = 4.dp),
            fontFamily = AppFonts.body, fontSize = 12.sp, color = Ink.copy(alpha = .55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ToolButton(icon: ImageVector, description: String, on: Boolean, onClick: () -> Unit, tint: Color? = null, content: (@Composable () -> Unit)? = null) {
    val glow by animateFloatAsState(if (on && tint == null) 1f else 0f, tween(220), label = "tool-on")
    val k = LocalPlayerScale.current
    Box(Modifier.size(44.dp * k).then(if (on && tint == null) Modifier.blurPane(RoundedCornerShape(16.dp), fill = .3f) else Modifier.clip(RoundedCornerShape(16.dp)))
        .graphicsLayer { val s = .94f + .06f * glow; scaleX = s; scaleY = s }
        .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        if (content != null) content() else Icon(icon, null, Modifier.size(22.dp * k), tint = tint ?: if (on) Active else Ink.copy(alpha = .75f))
    }
}

/** The name of where the sound goes now: a USB converter, Bluetooth headphones, wired headphones or the device. It follows devices that come and go. */
@Composable
private fun rememberOutputName(): String {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var devices by remember { mutableStateOf(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()) }
    DisposableEffect(audio) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
        }
        audio.registerAudioDeviceCallback(callback, null)
        onDispose { audio.unregisterAudioDeviceCallback(callback) }
    }
    val usb = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
    val bluetooth = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == 26 /* BLE headset */ || it.type == 27 /* BLE speaker */ }
    val wired = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
    return when {
        usb != null -> stringResource(R.string.rounded_output_usb, usb.productName?.toString()?.ifBlank { null } ?: "DAC")
        bluetooth != null -> bluetooth.productName?.toString()?.ifBlank { null } ?: stringResource(R.string.rounded_output_phone)
        wired != null -> stringResource(R.string.rounded_output_wired)
        else -> stringResource(R.string.rounded_output_phone)
    }
}

/**
 * The menu of the player, a card of glass that drops from the corner: three quick actions on top (like, spectrum, share), then the player's tools, then the
 * song's own places and the full menu of the song.
 */
@Composable
private fun GlassMenu(
    track: Track, saved: Boolean, lyrics: Boolean, onDismiss: () -> Unit,
    onLike: () -> Unit, onSpectrum: () -> Unit, onShare: () -> Unit, onTimer: () -> Unit, onPath: () -> Unit, onStems: () -> Unit, stemsOn: Boolean, onSpatial: () -> Unit, onDj: () -> Unit, onLyrics: () -> Unit, onQueue: () -> Unit,
    onAlbum: () -> Unit, onArtist: () -> Unit, onMore: () -> Unit,
) {
    fun act(action: () -> Unit) { onDismiss(); action() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().appEffect().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDismiss)) {
            Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 56.dp, end = 16.dp).widthIn(max = 320.dp).fillMaxWidth(.82f)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .9f), RoundedCornerShape(28.dp)).blurPane(RoundedCornerShape(28.dp), fill = .6f)
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState()).padding(vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    QuickAction(if (saved) OpenTuneIcons.Favorite else OpenTuneIcons.FavoriteBorder, stringResource(if (saved) R.string.rounded_dislike else R.string.track_save)) { act(onLike) }
                    QuickAction(OpenTuneIcons.GraphicEq, stringResource(R.string.rounded_menu_spectrum)) { act(onSpectrum) }
                    QuickAction(OpenTuneIcons.Share, stringResource(R.string.share_story_button)) { act(onShare) }
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = Ink.copy(alpha = .12f))
                MenuRow(OpenTuneIcons.Djai, stringResource(R.string.djai_name)) { act(onDj) }
                MenuRow(OpenTuneIcons.Lyrics, if (lyrics) stringResource(R.string.rounded_menu_cover) else stringResource(R.string.player_lyrics)) { act(onLyrics) }
                MenuRow(OpenTuneIcons.QueueMusic, stringResource(R.string.queue_title)) { act(onQueue) }
                MenuRow(OpenTuneIcons.Timer, stringResource(R.string.player_timer_label)) { act(onTimer) }
                MenuRow(OpenTuneIcons.Tune, stringResource(R.string.rounded_menu_path)) { act(onPath) }
                MenuRow(OpenTuneIcons.Equalizer, stringResource(R.string.stems_title), if (stemsOn) stringResource(R.string.stems_on_chip) else null) { act(onStems) }
                MenuRow(OpenTuneIcons.Spatial, stringResource(R.string.spatial_menu)) { act(onSpatial) }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = Ink.copy(alpha = .12f))
                if (track.albumId.isNotBlank() && !track.isFile) MenuRow(OpenTuneIcons.Album, stringResource(R.string.track_go_album), track.album) { act(onAlbum) }
                if (track.artistId.isNotBlank() && !track.isFile) MenuRow(OpenTuneIcons.Person, stringResource(R.string.track_go_artist), track.artist) { act(onArtist) }
                MenuRow(OpenTuneIcons.MoreHoriz, stringResource(R.string.rounded_menu_options)) { act(onMore) }
            }
        }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(26.dp), tint = Ink)
        Text(label, Modifier.padding(top = 6.dp), fontFamily = AppFonts.body, fontSize = 13.sp, color = Ink, maxLines = 1)
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, detail: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 24.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = Ink)
        Column(Modifier.padding(start = 18.dp)) {
            Text(label, fontFamily = AppFonts.body, fontSize = 17.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, fontFamily = AppFonts.body, fontSize = 13.sp, color = Ink.copy(alpha = .55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
