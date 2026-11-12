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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
        if (page == "stories") { renderStories(); return }
        val lyrics = intent.getBooleanExtra("lyrics", false)
        val sampleLyrics = Lyrics(listOf(
            "Lanterns on the water, slow and wide" to 52_000L, "Every window has a different tide" to 56_500L, "Walk with me past the closing shops" to 61_000L,
            "Where the evening hums and never stops" to 65_500L, "Hold the quiet, let the city fade" to 70_000L, "Night walk, night walk, all the way home" to 75_000L,
            "Streetlight stories, written in chalk" to 80_500L, "Footsteps keeping time on the stone" to 85_000L,
        ).map { LyricLine(it.second, it.first) }, "", "Example")
        val state = AppState(signedIn = true, settings = Settings(welcomeDone = true, djai = page == "playerdjai" || page == "playeralbum"),
            recent = tracks.take(3), favorites = tracks, albums = albums,
            searchQuery = if (page == "search") "Harbour Lights" else "", search = SearchResult(tracks = listOf(tracks[0]), albums = listOf(albums[0])),
            playback = PlaybackState(queue = tracks, index = 0, nextIndex = 1, duration = tracks[0].durationMs, quality = "FLAC · 24-bit · 192 kHz"),
            detail = if (page == "album") albums[0] else null, detailTracks = if (page == "album") tracks.take(1) else emptyList(),
            lyrics = if (lyrics) sampleLyrics else null, lyricsStatus = if (lyrics) LyricsStatus.Ready else LyricsStatus.Idle, lyricsTrackId = tracks[0].id)
        // The real engine publishes too when D'JAI is on, so the preview keeps its own state on screen by publishing it again and again.
        val planned = io.github.yyan115.opentune.playback.dj.DjUi(
            phase = io.github.yyan115.opentune.playback.dj.DjPhase.PLANNED, kind = io.github.yyan115.opentune.core.dj.MixKind.BEAT, beats = 48, blendBeats = 32, swap = 2.0 / 3.0,
            bpmOut = 124.0, bpmIn = 126.0, keyOut = "8A", keyIn = "9A", keyScore = 0.9, energyOut = 7.0, energyIn = 7.5, match = 0.93, speed = 0.984, startsInMs = 23_000, lengthMs = 23_000, nextTitle = "Slow Tide")
        if (page == "playerdjai") lifecycleScope.launch { while (true) { io.github.yyan115.opentune.playback.dj.DjMix.publish(planned); kotlinx.coroutines.delay(30) } }
        setContent { OpenTuneTheme {
            if (page == "icons") IconSheet()
            else CompositionLocalProvider(LocalOfflineMode provides true, LocalPlaybackPosition provides MutableStateFlow(64_000L),
                LocalTrackActions provides TrackActions(playNext = { android.util.Log.i("SwipePreview", "play next ${it.title}") }, addToQueue = { android.util.Log.i("SwipePreview", "add to queue ${it.title}") })) {
                OpenTuneRoot(model, state, initialDestination = when (page) { "search" -> Destination.Search; "library" -> Destination.Library; "downloads", "device" -> Destination.Device; else -> Destination.Home },
                    initialPlayer = page == "player" || page == "playerdjai" || page == "playeralbum", initialSettings = page == "settings", initialLyrics = lyrics)
            }
        } }
    }

    /** Renders every story layout to files, with a short and a long title and a full set of credits, so that the layouts can be checked on a screen. */
    private fun renderStories() {
        val out = File(filesDir, "story-preview").apply { deleteRecursively(); mkdirs() }
        val cover = File(out, "cover.png")
        val art = android.graphics.Bitmap.createBitmap(800, 800, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(art).apply {
            drawRect(0f, 0f, 800f, 800f, android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 800f, 800f, 0xFFD81B60.toInt(), 0xFF1A237E.toInt(), android.graphics.Shader.TileMode.CLAMP) })
            drawCircle(400f, 360f, 190f, android.graphics.Paint().apply { color = 0xFFFFC107.toInt() })
            drawText("COVER", 160f, 700f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 150f; isFakeBoldText = true })
        }
        cover.outputStream().use { art.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val credit = { role: String, names: List<String> -> io.github.yyan115.opentune.core.Credit(role, names.map { io.github.yyan115.opentune.core.Contributor(it) }) }
        val details = StoryDetails("2017-11-17", listOf(credit("Producer", listOf("Illangelo", "Doc McKinney", "Cirkut", "Frank Dukes")), credit("Writer", listOf("Abel Tesfaye", "Ahmad Balshe")),
            credit("Mixer", listOf("Illangelo")), credit("Mastering Engineer", listOf("Tom Coyne")), credit("Recording Engineer", listOf("Kevin Peterson")), credit("Executive Producer", listOf("The Weeknd"))))
        val variants = listOf("short" to ("Reminder" to "The Weeknd"), "long" to ("A Very Long Song Title That Has To Wrap Over Two Lines And Still Fit The Card" to "An Artist With A Very Long Name Feat. Another Artist & A Third Artist"))
        val appContext = applicationContext
        // If a layout never finishes, this prints where the renderer is spending its time.
        Thread {
            Thread.sleep(25_000)
            if (!File(out, "done").exists()) Thread.getAllStackTraces().forEach { (thread, frames) ->
                if (frames.any { it.className.contains("TidalStoryRenderer") }) android.util.Log.e("StoryWatchdog", "${thread.name}: " + frames.take(14).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" })
            }
        }.start()
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            for ((name, pair) in variants) for (layout in StoryLayout.entries) {
                val track = Track("1", pair.first, pair.second, "Album", "a1", cover.toURI().toString(), 200000)
                val bitmap = TidalStoryRenderer.generateStoryCard(appContext, track, "FLAC · 24-bit · 96.0 kHz", layout, if (layout.credits) details else StoryDetails())
                File(out, "${name}_${layout.name.lowercase()}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
            }
            File(out, "done").writeText("done")
        }
    }
}
