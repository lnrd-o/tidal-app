package io.github.yyan115.opentune.ui

import io.github.yyan115.opentune.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.yyan115.opentune.core.Track
import io.github.yyan115.opentune.core.formatDuration

/** The queue starts below its title and its tools. */
private const val HEADER_ROWS = 2

/** A row of the queue. The id is the identity of the row while it is dragged, because a track can stand in the queue more than once. */
private class QueueEntry(val uid: Long, val track: Track)

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun QueueSheet(state: AppState, vm: MainViewModel, onClose: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    var save by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val queue = state.playback.queue
    val current = state.playback.index
    val entries = remember { mutableStateListOf<QueueEntry>() }
    var nextUid by remember { mutableLongStateOf(0L) }
    var dragged by remember { mutableStateOf<Long?>(null) }
    var dragStart by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The sheet shows its own copy while a row is dragged. The player gets one move when the row is dropped.
    LaunchedEffect(queue, dragged) {
        if (dragged == null && entries.map { it.track.id } != queue.map { it.id }) { entries.clear(); queue.forEach { entries += QueueEntry(nextUid++, it) } }
    }
    LaunchedEffect(Unit) { if (current > 0) listState.scrollToItem((current + HEADER_ROWS - 1).coerceAtLeast(0)) }
    fun drag(delta: Float) {
        val uid = dragged ?: return
        dragOffset += delta
        val items = listState.layoutInfo.visibleItemsInfo
        val item = items.firstOrNull { it.key == uid } ?: return
        // A move changes the layout one frame later. Until the rows stand where the list says, their positions would give a wrong target.
        val shown = items.map { it.key }.filterIsInstance<Long>()
        if (shown != entries.map { it.uid }.filter { it in shown }) return
        val centre = item.offset + dragOffset + item.size / 2f
        val viewport = listState.layoutInfo
        if (centre < viewport.viewportStartOffset + 72) scope.launch { listState.scrollBy(-18f) }
        else if (centre > viewport.viewportEndOffset - 72) scope.launch { listState.scrollBy(18f) }
        val target = items.firstOrNull { it.key != uid && it.key is Long && centre >= it.offset && centre <= it.offset + it.size } ?: return
        val from = entries.indexOfFirst { it.uid == uid }; val to = entries.indexOfFirst { it.uid == target.key }
        if (from < 0 || to < 0) return
        entries.add(to, entries.removeAt(from))
        dragOffset += item.offset - target.offset
    }
    fun drop() {
        val uid = dragged ?: return
        val end = entries.indexOfFirst { it.uid == uid }
        if (dragStart >= 0 && end >= 0 && end != dragStart) vm.moveQueue(dragStart, end)
        dragged = null; dragStart = -1; dragOffset = 0f
    }
    ModalBottomSheet(shape = androidx.compose.ui.graphics.RectangleShape, dragHandle = { SheetHandle() }, onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().testTag("queue-list"), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "title") { SectionTitle(stringResource(R.string.queue_title), if (queue.isNotEmpty()) stringResource(R.string.action_clear) else null, { confirmClear = true }) }
            if (queue.isNotEmpty()) item(key = "tools") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { name = state.playback.source; save = true }) {
                        Icon(OpenTuneIcons.PlaylistAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.queue_save))
                    }
                    Spacer(Modifier.weight(1f))
                    if (state.playback.shuffle) Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(OpenTuneIcons.Shuffle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.queue_shuffled), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (queue.isEmpty()) item(key = "empty") { EmptyState(OpenTuneIcons.PlaylistPlay, stringResource(R.string.queue_empty_title), stringResource(R.string.queue_empty_text)) }
            itemsIndexed(entries, key = { _, entry -> entry.uid }) { index, entry ->
                val track = entry.track
                val playing = index == current && dragged == null || dragged != null && entry.track === queue.getOrNull(current)
                val lifted = dragged == entry.uid
                val moveUp = stringResource(R.string.queue_move_up, track.title)
                val moveDown = stringResource(R.string.queue_move_down, track.title)
                val remove = stringResource(R.string.queue_remove, track.title)
                val nowPlayingLabel = stringResource(R.string.queue_now_playing)
                val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                    if (value != SwipeToDismissBoxValue.Settled && dragged == null) { entries.removeAll { it.uid == entry.uid }; vm.removeQueue(index); true } else false
                })
                SwipeToDismissBox(dismiss, modifier = Modifier.animateItem().zIndex(if (lifted) 1f else 0f).graphicsLayer { if (lifted) translationY = dragOffset },
                    backgroundContent = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp), contentAlignment = Alignment.CenterEnd) {
                        Icon(OpenTuneIcons.Close, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    } }) {
                    Surface(color = if (lifted) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = if (lifted) 6.dp else 0.dp) {
                        Row(Modifier.fillMaxWidth().clickable { vm.jumpTo(index) }.padding(start = 24.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                            .semantics { customActions = listOf(
                                CustomAccessibilityAction(moveUp) { if (index > 0) { entries.add(index - 1, entries.removeAt(index)); vm.moveQueue(index, index - 1); true } else false },
                                CustomAccessibilityAction(moveDown) { if (index < entries.lastIndex) { entries.add(index + 1, entries.removeAt(index)); vm.moveQueue(index, index + 1); true } else false },
                                CustomAccessibilityAction(remove) { entries.removeAll { it.uid == entry.uid }; vm.removeQueue(index); true }) },
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(contentAlignment = Alignment.Center) {
                                TrackArtwork(track, null, Modifier.size(44.dp).graphicsLayer { alpha = if (index < current) .5f else 1f })
                                if (playing) Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = .55f), MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                                    PlayingBars(state.playback.playing, Modifier.semantics { contentDescription = nowPlayingLabel })
                                }
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = if (index < current) .6f else 1f))
                                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (track.isFile) LocalBadge(Modifier.padding(end = 6.dp))
                                    Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Icon(OpenTuneIcons.DragHandle, stringResource(R.string.queue_reorder, track.title), Modifier.size(44.dp).padding(10.dp).pointerInput(entry.uid) {
                                detectDragGestures(
                                    onDragStart = { dragged = entry.uid; dragStart = entries.indexOfFirst { it.uid == entry.uid }; dragOffset = 0f },
                                    onDragEnd = { drop() }, onDragCancel = { drop() }, onDrag = { change, amount -> change.consume(); drag(amount.y) })
                            }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    if (save) NewPlaylistDialog(accountAvailable = state.signedIn && !state.settings.offline && queue.none(Track::isFile), initialName = name,
        onCreate = { title, home -> vm.saveQueueAsPlaylist(title, home); save = false }, onClose = { save = false })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text(stringResource(R.string.queue_clear_title)) }, text = { Text(stringResource(R.string.queue_clear_text)) },
        confirmButton = { TextButton(onClick = { vm.clearQueue(); confirmClear = false }) { Text(stringResource(R.string.action_clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } })
}
