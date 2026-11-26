package io.github.lnrdo.mytidal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import io.github.lnrdo.mytidal.R
import io.github.lnrdo.mytidal.core.Track
import io.github.lnrdo.mytidal.stems.StemModel
import io.github.lnrdo.mytidal.stems.StemStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The space that the stems may take, in megabytes. */
private val StemSpaces = listOf(500, 1500, 3000, 6000)

/**
 * Stems in the settings: how songs are separated (the model, automatic by default; the NPU; the sharpness of the masks; the space for stems) and the songs
 * whose stems are kept, separated last first. A song opens its stems' mixer; its stems can be deleted.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StemsPage(state: AppState, vm: MainViewModel) {
    val context = LocalContext.current
    val store = vm.app.stems.store
    val progress by vm.app.stems.state.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    var songs by remember { mutableStateOf<List<StemStore.Kept>?>(null) }
    // A song that finishes separating appears in the list.
    LaunchedEffect(version, progress.values.count { it.phase == io.github.lnrdo.mytidal.stems.StemProgress.Phase.DONE }) { songs = withContext(Dispatchers.IO) { store.list() } }
    var open by remember { mutableStateOf<Track?>(null) }
    val installed = remember { StemModel.entries.filter { it.installed(context) } }
    val best = remember { StemModel.best(context) }
    val settings = state.settings
    val list = songs

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionTitle(stringResource(R.string.stems_page_model)) }
        item {
            Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(settings.stemsModel == "auto", { vm.settings { it.copy(stemsModel = "auto") } }, { Text(stringResource(R.string.stems_page_auto)) })
                    installed.forEach { m -> FilterChip(settings.stemsModel == m.id, { vm.settings { it.copy(stemsModel = m.id) } }, { Text(modelName(m), maxLines = 1) }) }
                }
                if (best != null) Text(stringResource(R.string.stems_page_auto_text, modelName(best)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { SwitchRow(OpenTuneIcons.Device, stringResource(R.string.stems_page_npu), settings.stemsNpu, { on -> vm.settings { it.copy(stemsNpu = on) } }, stringResource(R.string.stems_page_npu_text)) }
        item {
            // How steep the cut between the stems is, for the models that share every bin by masks (not SCNet).
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.stems_sharpness), style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(2 to R.string.stems_sharp_standard, 3 to R.string.stems_sharp_sharper, 4 to R.string.stems_sharp_sharpest).forEachIndexed { i, (p, name) ->
                        SegmentedButton(settings.stemsPower == p, { vm.settings { it.copy(stemsPower = p) } }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(stringResource(name), fontSize = 12.sp, maxLines = 1) }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.stems_page_space), style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    StemSpaces.forEachIndexed { i, mb ->
                        SegmentedButton(settings.stemsCacheMb == mb, { vm.settings { it.copy(stemsCacheMb = mb) } }, SegmentedButtonDefaults.itemShape(i, StemSpaces.size)) { Text(formatSize(mb * 1_000_000L), fontSize = 12.sp, maxLines = 1) }
                    }
                }
                Text(stringResource(R.string.stems_page_used, formatSize(list.orEmpty().sumOf { it.bytes }), list?.size ?: 0, formatSize(settings.stemsCacheMb * 1_000_000L)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { SectionTitle(stringResource(R.string.stems_page_songs)) }
        if (list != null && list.isEmpty()) item { EmptyState(OpenTuneIcons.Equalizer, stringResource(R.string.stems_page_songs), stringResource(R.string.stems_page_empty)) }
        items(list.orEmpty(), key = { it.folder.name }) { kept ->
            val track = kept.track
            Row(Modifier.fillMaxWidth().clickable(enabled = track != null) { open = track }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (track != null) TrackArtwork(track, null, Modifier.size(48.dp)) else Spacer(Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track?.title?.takeIf { it.isNotBlank() } ?: stringResource(R.string.stems_page_unknown), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val model = kept.model?.let { id -> StemModel.entries.firstOrNull { it.id == id } }
                    Text(listOfNotNull(track?.artist?.takeIf { it.isNotBlank() }, model?.let { modelName(it) }, formatSize(kept.bytes)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton({ kept.folder.deleteRecursively(); version++ }) { Icon(OpenTuneIcons.RemoveCircleOutline, stringResource(R.string.stems_page_delete)) }
            }
        }
        if (!list.isNullOrEmpty()) item { TextButton({ store.clear(); version++ }, Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.stems_page_delete_all)) } }
    }
    open?.let { track -> StemsSheet(track, state, vm) { open = null; version++ } }
}
