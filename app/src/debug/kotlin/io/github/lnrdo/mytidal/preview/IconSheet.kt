package io.github.lnrdo.mytidal.preview

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import io.github.lnrdo.mytidal.ui.*

@Composable
fun IconSheet() {
    val context = LocalContext.current
    val launcher = remember { context.packageManager.getApplicationIcon(context.packageName).toBitmap(240, 240).asImageBitmap() }
    val icons = listOf(
        "Listen" to OpenTuneIcons.Headphones, "Search" to OpenTuneIcons.Search, "Library" to OpenTuneIcons.LibraryMusic,
        "Download" to OpenTuneIcons.Download, "Play" to OpenTuneIcons.PlayArrow, "Pause" to OpenTuneIcons.Pause,
        "Previous" to OpenTuneIcons.SkipPrevious, "Next" to OpenTuneIcons.SkipNext,
        "Save" to OpenTuneIcons.FavoriteBorder, "Queue" to OpenTuneIcons.PlaylistPlay, "Lyrics" to OpenTuneIcons.Lyrics,
        "Source" to OpenTuneIcons.GraphicEq, "Settings" to OpenTuneIcons.Settings, "Offline" to OpenTuneIcons.CloudOff,
        "Shuffle" to OpenTuneIcons.Shuffle, "Repeat" to OpenTuneIcons.Repeat,
        "Album" to OpenTuneIcons.Album, "Share" to OpenTuneIcons.Share, "Export" to OpenTuneIcons.SaveAlt,
        "Appearance" to OpenTuneIcons.Contrast,
    )
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Image(launcher, "OpenTune launcher", Modifier.size(78.dp))
                Column { Text("OpenTune", style = AppFonts.title(40, 46)); UpperLabel("Icon family") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyVerticalGrid(GridCells.Fixed(4), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                items(icons) { (title, icon) ->
                    Column(Modifier.height(70.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(icon, title, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.onSurface)
                        Text(title, Modifier.padding(top = 12.dp), fontFamily = AppFonts.body, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
