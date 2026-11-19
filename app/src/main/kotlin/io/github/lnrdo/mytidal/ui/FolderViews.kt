package io.github.lnrdo.mytidal.ui

import io.github.lnrdo.mytidal.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.lnrdo.mytidal.core.Release
import io.github.lnrdo.mytidal.core.ReleaseType
import io.github.lnrdo.mytidal.core.TidalClient

/** A playlist folder in the library. */
@Composable
fun FolderRow(folder: Release, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(60.dp), shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Box(contentAlignment = Alignment.Center) { Icon(OpenTuneIcons.Folder, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary) }
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(folder.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(pluralStringResource(R.plurals.folder_summary, folder.subtitle.toIntOrNull() ?: 0, folder.subtitle.toIntOrNull() ?: 0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(OpenTuneIcons.MoreVert, stringResource(R.string.folder_options, folder.title), Modifier.size(20.dp)) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.action_rename)) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.folder_delete)) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/** A TIDAL playlist in the library, with an option to move it into a folder when it belongs to the listener. */
@Composable
fun PlaylistRow(playlist: Release, canMove: Boolean, onMove: () -> Unit, onOpen: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 24.dp, end = if (canMove) 8.dp else 24.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(playlist.artwork, null, Modifier.size(60.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(playlist.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(playlist.artist.ifBlank { playlist.subtitle }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (canMove) IconButton(onClick = onMove) { Icon(OpenTuneIcons.Folder, stringResource(R.string.playlist_move_description, playlist.title), Modifier.size(20.dp)) }
        else Icon(OpenTuneIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onClose, title = { Text(title) }, text = { OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.field_name)) }, singleLine = true) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()); onClose() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } })
}

@Composable
fun MoveToFolderDialog(playlist: Release, folders: List<Release>, onPick: (String) -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.move_title, playlist.title)) }, text = {
        Column {
            TextButton(onClick = { onPick(TidalClient.ROOT_FOLDER); onClose() }) { Text(stringResource(R.string.folder_top_level)) }
            folders.filter { it.type == ReleaseType.FOLDER }.forEach { folder -> TextButton(onClick = { onPick(folder.id); onClose() }) { Text(folder.title) } }
            if (folders.none { it.type == ReleaseType.FOLDER }) Text(stringResource(R.string.folder_none_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } })
}
