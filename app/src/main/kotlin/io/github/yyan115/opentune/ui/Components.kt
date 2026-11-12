package io.github.yyan115.opentune.ui

import io.github.yyan115.opentune.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.request.crossfade
import androidx.compose.ui.platform.LocalContext
import io.github.yyan115.opentune.core.Release
import io.github.yyan115.opentune.core.Track
import kotlinx.coroutines.delay

val LocalOfflineMode = staticCompositionLocalOf { false }

/** Changes whenever the device regains a network, so that pictures that failed to load are requested again. */
val LocalNetworkEpoch = staticCompositionLocalOf { 0 }

private val ArtworkRetryDelays = listOf(2_000L, 5_000L, 12_000L)

/**
 * A cover picture. A load that fails is requested again after two, five and twelve seconds.
 * When the device regains a network, the schedule starts again at once, because a request made while it was away fails.
 */
@Composable
fun Artwork(uri: String?, description: String?, modifier: Modifier = Modifier, placeholderSeed: String? = null) {
    val context = LocalContext.current
    val offline = LocalOfflineMode.current
    val epoch = LocalNetworkEpoch.current
    var attempt by remember(uri) { mutableIntStateOf(0) }
    var failures by remember(uri) { mutableIntStateOf(0) }
    val request = remember(uri, offline, context, attempt) { ImageRequest.Builder(context).data(uri)
        .crossfade(true).networkCachePolicy(if (offline) CachePolicy.DISABLED else CachePolicy.ENABLED)
        .listener(onError = { r, result -> failures++; android.util.Log.w("OpenTune", "Cover ${r.data.toString().takeLast(36)} failed: ${result.throwable.javaClass.simpleName}") },
            onSuccess = { _, _ -> failures = 0 }).build() }
    LaunchedEffect(epoch) { if (failures > 0) { failures = 0; attempt++ } }
    LaunchedEffect(failures) {
        if (failures in 1..ArtworkRetryDelays.size && !offline) { delay(ArtworkRetryDelays[failures - 1]); attempt++ }
    }
    Box(modifier.clip(MaterialTheme.shapes.small).background(placeholderBrush(placeholderSeed)), contentAlignment = Alignment.Center) {
        if (placeholderSeed != null) Icon(OpenTuneIcons.MusicNote, null, Modifier.fillMaxSize(.34f), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f))
        else Icon(OpenTuneIcons.Album, null, Modifier.fillMaxSize(.32f), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f))
        // A new key makes a new image painter, which loads again. A changed request alone does not always restart a load.
        if (uri != null) key(attempt) { AsyncImage(request, description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** A plain tile, or a tile whose colours follow [seed], so that the files of one album share a colour when they carry no picture. */
@Composable
private fun placeholderBrush(seed: String?): Brush {
    val base = MaterialTheme.colorScheme.surfaceVariant
    if (seed == null) return androidx.compose.ui.graphics.SolidColor(base)
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val hue = ((seed.lowercase().hashCode() % 360) + 360) % 360
    val value = if (dark) .34f else .88f
    return Brush.linearGradient(listOf(Color.hsv(hue.toFloat(), if (dark) .38f else .22f, value), Color.hsv(((hue + 48) % 360).toFloat(), if (dark) .5f else .3f, if (dark) .22f else .78f)))
}

/** The cover of a track. A file without a picture gets a coloured tile with a note, which tells it from a TIDAL track whose cover is still loading. */
@Composable
fun TrackArtwork(track: Track, description: String?, modifier: Modifier = Modifier) =
    Artwork(track.artwork, description, modifier, if (track.isFile && track.artwork == null) track.album.ifBlank { track.artist.ifBlank { track.title } } else null)

/** Marks a file that the listener added from the device. A TIDAL track carries no mark, because TIDAL is what the app is for. */
@Composable
fun LocalBadge(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.label_local).uppercase(), modifier.border(1.dp, MaterialTheme.colorScheme.outline, androidx.compose.ui.graphics.RectangleShape).padding(horizontal = 4.dp, vertical = 1.dp),
        fontFamily = AppFonts.body, fontSize = 9.sp, lineHeight = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = AppFonts.title(27, 32))
        if (action != null) TextButton(onClick = onAction) {
            Text(action, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(7.dp)); Icon(OpenTuneIcons.ArrowForward, null, Modifier.size(16.dp))
        }
    }
}

/** Three square bars that rise and fall while a track plays. They draw without recomposing. */
@Composable
fun PlayingBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = Color.White) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "playing-bars")
    val a by transition.animateFloat(.25f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(520, easing = androidx.compose.animation.core.LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    val b by transition.animateFloat(1f, .3f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(430, easing = androidx.compose.animation.core.LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "b")
    val c by transition.animateFloat(.4f, .9f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(610, easing = androidx.compose.animation.core.LinearEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "c")
    androidx.compose.foundation.Canvas(modifier.size(18.dp)) {
        val bar = size.width / 5f
        listOf(a, b, c).forEachIndexed { index, level ->
            val h = size.height * if (playing) level else .25f
            drawRect(color, androidx.compose.ui.geometry.Offset(index * bar * 2f, size.height - h), androidx.compose.ui.geometry.Size(bar, h))
        }
    }
}

@Composable
fun SheetHandle() { androidx.compose.foundation.layout.Box(Modifier.padding(vertical = 10.dp).size(32.dp, 3.dp).background(MaterialTheme.colorScheme.outline)) }

@Composable
fun EmptyState(icon: ImageVector, title: String, description: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = androidx.compose.ui.graphics.RectangleShape, color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Icon(icon, null, Modifier.padding(24.dp).size(34.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, Modifier.padding(top = 24.dp), style = MaterialTheme.typography.headlineSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(description, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) TuneTonalButton(onClick = onAction, modifier = Modifier.padding(top = 24.dp)) { Text(action) }
    }
}

@Composable
fun ErrorCard(message: String, onRetry: (() -> Unit)? = null) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(16.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
            if (onRetry != null) TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry), color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

/** What a swipe on a song does. The activity provides them, because they act on the queue of the player. */
class TrackActions(val playNext: (Track) -> Unit, val addToQueue: (Track) -> Unit)

val LocalTrackActions = staticCompositionLocalOf<TrackActions?> { null }

/**
 * A song in a list. A tap plays it and a long press opens its menu. A swipe to the right plays it next, and a swipe to the left adds it
 * to the end of the queue. The row springs back after the swipe, and the message of the player says what happened.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TrackRow(track: Track, playing: Boolean = false, onPlay: () -> Unit, onMenu: () -> Unit, index: Int? = null) {
    val actions = LocalTrackActions.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        when (value) {
            SwipeToDismissBoxValue.StartToEnd -> { actions?.playNext?.invoke(track); haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
            SwipeToDismissBoxValue.EndToStart -> { actions?.addToQueue?.invoke(track); haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
            SwipeToDismissBoxValue.Settled -> Unit
        }
        // The row never leaves: it springs back, so that the list stays as it is.
        false
    })
    SwipeToDismissBox(state, enableDismissFromStartToEnd = actions != null, enableDismissFromEndToStart = actions != null, backgroundContent = {
        val toEnd = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
        val shown = state.dismissDirection != SwipeToDismissBoxValue.Settled
        Row(Modifier.fillMaxSize().background(if (shown) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent).padding(horizontal = 24.dp),
            horizontalArrangement = if (toEnd) Arrangement.Start else Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (shown) {
                if (toEnd) { Icon(OpenTuneIcons.PlaylistPlay, null, Modifier.size(22.dp)); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.track_play_next), style = MaterialTheme.typography.labelLarge) }
                else { Text(stringResource(R.string.track_add_queue), style = MaterialTheme.typography.labelLarge); Spacer(Modifier.width(12.dp)); Icon(OpenTuneIcons.QueueMusic, null, Modifier.size(22.dp)) }
            }
        }
    }) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).combinedClickable(onClick = onPlay, onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onMenu() })
            .padding(start = 24.dp, end = 8.dp, top = 11.dp, bottom = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            if (index != null) Text("%02d".format(index + 1), Modifier.width(32.dp), fontFamily = AppFonts.mono, fontSize = 10.sp,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            else TrackArtwork(track, null, Modifier.size(48.dp))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (track.explicit) {
                        Text("E", Modifier.padding(end = 6.dp).background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.ui.graphics.RectangleShape).padding(horizontal = 3.dp),
                            style = MaterialTheme.typography.labelSmall)
                    }
                    if (track.isFile) LocalBadge(Modifier.padding(end = 6.dp))
                    else if (track.onDevice) Icon(OpenTuneIcons.DownloadDone, stringResource(R.string.track_available_offline), Modifier.padding(end = 4.dp).size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(track.artist, fontFamily = AppFonts.body, fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onMenu) { Icon(OpenTuneIcons.MoreVert, stringResource(R.string.track_options, track.title), Modifier.size(20.dp)) }
        }
    }
}

@Composable
fun ReleaseCard(release: Release, onClick: () -> Unit) {
    Column(Modifier.width(152.dp).clickable(onClick = onClick).padding(4.dp)) {
        Artwork(release.artwork, null, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(release.title, Modifier.padding(top = 10.dp), fontFamily = AppFonts.body, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(release.artist.ifBlank { release.subtitle }, Modifier.padding(top = 3.dp), fontFamily = AppFonts.body, fontSize = 10.sp, lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SettingRow(icon: ImageVector, title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .8f))
        Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Text(subtitle, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

/** A setting that is switched on or off. The whole row responds to a tap, not only the switch. */
@Composable
fun SwitchRow(icon: ImageVector, title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .8f))
        Column(Modifier.weight(1f).padding(horizontal = 18.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Text(subtitle, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, null)
    }
}

@Composable
fun UpperLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, fontFamily = AppFonts.body, fontSize = 10.sp, lineHeight = 16.sp,
        letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun TuneButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    androidx.compose.material3.Button(onClick, modifier, enabled, shape = androidx.compose.ui.graphics.RectangleShape,
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 13.dp), content = content)
}

@Composable
fun TuneTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    FilledTonalButton(onClick, modifier, enabled, shape = androidx.compose.ui.graphics.RectangleShape,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp), content = content)
}

@Composable
fun TuneOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 13.dp), content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick, modifier, enabled, shape = androidx.compose.ui.graphics.RectangleShape, contentPadding = contentPadding, content = content)
}
