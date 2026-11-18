package io.github.yyan115.opentune.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yyan115.opentune.R
import io.github.yyan115.opentune.core.Track
import io.github.yyan115.opentune.core.dj.AutoMixKind
import io.github.yyan115.opentune.core.dj.Camelot
import io.github.yyan115.opentune.playback.dj.DjMix

/** A mix that is ready to play: what it is, and its songs in the order that mixes best. */
class MixCard(val kind: AutoMixKind, val tracks: List<Track>)

/**
 * The auto mixes: playlists that D'JAI builds from the songs that it has analysed, each in an order that gives good transitions. One is the journey, a set that
 * climbs in energy; the others keep to one energy, one band of tempo or one family of keys. Playing one keeps its order, and D'JAI mixes it as a set.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun MixesScreen(state: AppState, vm: MainViewModel) {
    val kept by DjMix.known.collectAsStateWithLifecycle()
    var cards by remember { mutableStateOf<List<MixCard>?>(null) }
    LaunchedEffect(kept) { cards = vm.buildMixes() }
    val list = cards
    if (list == null) return
    if (list.isEmpty()) {
        EmptyState(OpenTuneIcons.Waves, stringResource(R.string.mixes_empty_title), stringResource(R.string.mixes_empty_text, io.github.yyan115.opentune.core.dj.AutoMixes.MIN_SONGS))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (!state.settings.djai) item {
            Text(stringResource(R.string.mixes_djai_off), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(list, key = { it.kind.key() }) { card -> MixRow(card) { vm.play(card.tracks, 0, it, shuffle = false) } }
    }
}

@Composable
private fun MixRow(card: MixCard, onPlay: (String) -> Unit) {
    val title = mixTitle(card.kind)
    val minutes = (card.tracks.sumOf { it.durationMs } / 60_000).toInt()
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.mixes_summary, card.tracks.size, minutes / 60, minutes % 60), fontFamily = AppFonts.mono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.action_play), Modifier.border(1.dp, Color.White).clickable { onPlay(title) }.padding(horizontal = 14.dp, vertical = 8.dp),
                fontFamily = AppFonts.body, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (track in card.tracks.take(5)) TrackArtwork(track, null, Modifier.size(56.dp))
        }
        Text(card.tracks.take(3).joinToString(" · ") { it.title }, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

/** The name of a mix, from what it is made of. */
@Composable
fun mixTitle(kind: AutoMixKind): String = when (kind) {
    is AutoMixKind.Journey -> stringResource(R.string.mixes_journey)
    is AutoMixKind.Energy -> stringResource(when (kind.level) { 0 -> R.string.mixes_calm; 1 -> R.string.mixes_groove; else -> R.string.mixes_peak })
    is AutoMixKind.Tempo -> stringResource(R.string.mixes_tempo, kind.from, kind.to)
    is AutoMixKind.Harmonic -> stringResource(R.string.mixes_keys, Camelot.noteName(kind.centre)?.let { "${kind.centre} $it" } ?: kind.centre)
    is AutoMixKind.Genre -> stringResource(genreName(kind.family))
}

/** The name of a family of style, as the lists and the mixes show it. */
fun genreName(family: io.github.yyan115.opentune.core.dj.GenreFamily): Int = when (family) {
    io.github.yyan115.opentune.core.dj.GenreFamily.ELECTRONIC -> R.string.genre_electronic
    io.github.yyan115.opentune.core.dj.GenreFamily.BASS -> R.string.genre_bass
    io.github.yyan115.opentune.core.dj.GenreFamily.HIPHOP -> R.string.genre_hiphop
    io.github.yyan115.opentune.core.dj.GenreFamily.LATIN -> R.string.genre_latin
    io.github.yyan115.opentune.core.dj.GenreFamily.POP -> R.string.genre_pop
    io.github.yyan115.opentune.core.dj.GenreFamily.ROCK -> R.string.genre_rock
    io.github.yyan115.opentune.core.dj.GenreFamily.SOUL -> R.string.genre_soul
    io.github.yyan115.opentune.core.dj.GenreFamily.CHILL -> R.string.genre_chill
}

private fun AutoMixKind.key(): String = when (this) {
    is AutoMixKind.Journey -> "journey"
    is AutoMixKind.Energy -> "energy$level"
    is AutoMixKind.Tempo -> "tempo$from"
    is AutoMixKind.Harmonic -> "keys$centre"
    is AutoMixKind.Genre -> "genre${family.name}"
}
