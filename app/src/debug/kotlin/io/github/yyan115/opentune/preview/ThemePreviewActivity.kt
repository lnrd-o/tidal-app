package io.github.yyan115.opentune.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import io.github.yyan115.opentune.core.LyricLine
import io.github.yyan115.opentune.core.Lyrics
import io.github.yyan115.opentune.core.Release
import io.github.yyan115.opentune.core.Track
import io.github.yyan115.opentune.core.SearchResult
import io.github.yyan115.opentune.data.Settings
import io.github.yyan115.opentune.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Reproducible visual checks of the production components; never included in a release APK. */
@UnstableApi
class ThemePreviewActivity : ComponentActivity() {
    companion object {
        /** The names and lines of the sample music. Tests know them as data, not as interface text. */
        val SAMPLE_TEXT = setOf("Night Walk", "Glass Rooms", "Slow Tide", "Paper Sun", "Harbour Lights", "Maren Vale", "Oskar Lind", "Maren Vale, Oskar Lind", "Late Hours", "Salt and Glass", "Example",
            "Lanterns on the water, slow and wide", "Every window has a different tide", "Walk with me past the closing shops", "Where the evening hums and never stops",
            "Hold the quiet, let the city fade", "Night walk, night walk, all the way home", "Streetlight stories, written in chalk", "Footsteps keeping time on the stone", "FLAC")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
        val model = ViewModelProvider(this)[MainViewModel::class.java]
        val covers = File(filesDir, "theme-preview")
        fun cover(name: String) = covers.resolve("$name.jpg").takeIf(File::isFile)?.toURI()?.toString()
        val lateHours = cover("late-hours"); val saltAndGlass = cover("salt-and-glass")
        val tracks = listOf(
            Track("preview:night-walk", "Night Walk", "Harbour Lights", "Late Hours", "preview:late-hours", lateHours, 186674),
            Track("preview:glass-rooms", "Glass Rooms", "Harbour Lights", "Late Hours", "preview:late-hours", lateHours, 211000),
            Track("preview:slow-tide", "Slow Tide", "Maren Vale", "Salt and Glass", "preview:salt-and-glass", saltAndGlass, 175000),
            Track("preview:paper-sun", "Paper Sun", "Maren Vale, Oskar Lind", "Salt and Glass", "preview:salt-and-glass", saltAndGlass, 257000),
        )
        val albums = listOf(Release("preview:late-hours", "Late Hours", "Harbour Lights", lateHours),
            Release("preview:salt-and-glass", "Salt and Glass", "Maren Vale", saltAndGlass))
        val page = intent.getStringExtra("page")
        val lyrics = intent.getBooleanExtra("lyrics", false)
        val sampleLyrics = Lyrics(listOf(
            "Lanterns on the water, slow and wide" to 52_000L, "Every window has a different tide" to 56_500L, "Walk with me past the closing shops" to 61_000L,
            "Where the evening hums and never stops" to 65_500L, "Hold the quiet, let the city fade" to 70_000L, "Night walk, night walk, all the way home" to 75_000L,
            "Streetlight stories, written in chalk" to 80_500L, "Footsteps keeping time on the stone" to 85_000L,
        ).map { LyricLine(it.second, it.first) }, "", "Example")
        val state = AppState(signedIn = true, settings = Settings(welcomeDone = true),
            recent = tracks.take(3), favorites = tracks, albums = albums,
            searchQuery = if (page == "search") "Harbour Lights" else "", search = SearchResult(tracks = listOf(tracks[0]), albums = listOf(albums[0])),
            playback = PlaybackState(queue = tracks, index = 0, nextIndex = 1, duration = tracks[0].durationMs, quality = "FLAC · 24-bit · 192 kHz"),
            detail = if (page == "album") albums[0] else null, detailTracks = if (page == "album") tracks.take(1) else emptyList(),
            lyrics = if (lyrics) sampleLyrics else null, lyricsStatus = if (lyrics) LyricsStatus.Ready else LyricsStatus.Idle, lyricsTrackId = tracks[0].id)
        setContent { OpenTuneTheme {
            if (page == "icons") IconSheet()
            else CompositionLocalProvider(LocalOfflineMode provides true, LocalPlaybackPosition provides MutableStateFlow(64_000L)) {
                OpenTuneRoot(model, state, initialDestination = when (page) { "search" -> Destination.Search; "library" -> Destination.Library; "downloads", "device" -> Destination.Device; else -> Destination.Home },
                    initialPlayer = page == "player", initialSettings = page == "settings", initialLyrics = lyrics)
            }
        } }
    }
}
