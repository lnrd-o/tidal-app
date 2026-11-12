package io.github.yyan115.opentune

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yyan115.opentune.core.Track
import io.github.yyan115.opentune.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@UnstableApi
@RunWith(AndroidJUnit4::class)
class LocalPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var app: OpenTuneApp
    private lateinit var model: MainViewModel
    private lateinit var track: Track

    @Before fun seedOriginalTestAudio() {
        app = compose.activity.application as OpenTuneApp
        model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnIdle {
            model.clearQueue()
            model.settings { it.copy(offline = false, welcomeDone = true) }
        }
        app.library.playlists().filter { it.title == "Evening" }.forEach { app.library.deletePlaylist(it.id) }
        val file = File(app.filesDir, "playback-test.flac")
        InstrumentationRegistry.getInstrumentation().context.assets.open("night-walk.flac").use { input -> file.outputStream().use(input::copyTo) }
        track = Track("local:playback-test", "Night Walk", "Playback test", "Local audio checks", durationMs = 30_000,
            localUri = Uri.fromFile(file).toString(), quality = "FLAC · 24-bit · 96 kHz")
        app.library.save(track)
        compose.waitUntil(10_000) { model.state.value.local.any { it.id == track.id } }
    }

    @Test fun localFlacPlaysThroughTheInterfaceAndPauses() {
        compose.onNodeWithTag("device-list").performScrollToNode(hasText("Night Walk"))
        compose.onNodeWithText("Night Walk").performClick()
        compose.waitUntil(25_000) { model.state.value.playback.playing || model.state.value.playback.error != null }
        assertNull(model.state.value.playback.error)
        assertTrue("FLAC did not begin playing", model.state.value.playback.playing)
        compose.waitUntil(10_000) { model.position.value > 500 }
        screenshot("library-playing")
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.waitUntil(5_000) { !model.state.value.playback.playing }
        assertTrue(model.state.value.playback.duration > 29_000)
    }

    @Test fun queueCanBeEditedWithoutLosingDuplicateTracks() {
        compose.runOnIdle { model.play(listOf(track, track.copy(id = "local:playback-test-2", title = "Second Track"), track)) }
        compose.waitUntil(10_000) { model.state.value.playback.queue.size == 3 }
        compose.onNodeWithTag("mini-player").performClick()
        screenshot("now-playing")
        compose.onNodeWithText("Queue · 3").performClick()
        // A row leaves the queue with a swipe.
        compose.onNode(hasContentDescription("Reorder Second Track")).performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { model.state.value.playback.queue.size == 2 }
        assertEquals(listOf(track.id, track.id), model.state.value.playback.queue.map(Track::id))
        screenshot("queue")
    }

    @Test fun localFlacRemainsPlayableInOfflineMode() {
        compose.runOnIdle { model.settings { it.copy(offline = true) }; model.play(listOf(track)) }
        compose.waitUntil(15_000) { model.state.value.playback.playing || model.state.value.playback.error != null }
        assertNull(model.state.value.playback.error)
        assertTrue(model.state.value.playback.playing)
        compose.waitUntil(5_000) { model.position.value > 500 }
        compose.runOnIdle { model.seek(15_000) }
        compose.waitUntil(5_000) { model.position.value in 15_000..25_000 }
        compose.runOnIdle { model.togglePlay() }
    }

    @Test fun sourceDetailsReportThePlayingFlacFormat() {
        compose.runOnIdle { model.play(listOf(track)) }
        compose.waitUntil(15_000) { model.state.value.playback.playing || model.state.value.playback.error != null }
        assertNull(model.state.value.playback.error)
        compose.waitUntil(5_000) { model.position.value > 500 }
        compose.onNodeWithTag("mini-player").performClick()
        compose.onNodeWithContentDescription("Audio source details").performClick()
        compose.onNodeWithTag("source-codec").assertTextEquals("FLAC")
        compose.onNodeWithTag("source-bits").assertTextEquals("24 bit")
        compose.onNodeWithTag("source-rate").assertTextEquals("96 kHz")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Close player").performClick()
        compose.runOnIdle { if (model.state.value.playback.playing) model.togglePlay() }
    }

    @Test fun localPlaylistCreationAndSavingArePersistent() {
        compose.onNodeWithTag("device-list").performScrollToNode(hasText("New playlist"))
        compose.onNodeWithText("New playlist").performClick()
        compose.onNodeWithText("Name").performTextInput("Evening")
        compose.onNodeWithText("Create", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { app.library.playlists().any { it.title == "Evening" } }
        val playlist = app.library.playlists().first { it.title == "Evening" }
        app.library.addToPlaylist(playlist.id, track)
        compose.waitUntil(5_000) { model.state.value.localPlaylists.any { it.id == playlist.id && it.trackIds.size == 1 } }
        compose.onNodeWithTag("device-list").performScrollToNode(hasText("Evening"))
        compose.onNodeWithText("Evening").performClick()
        compose.onNodeWithTag("detail-list").performScrollToNode(hasText("Night Walk"))
        compose.onNodeWithText("Night Walk").assertIsDisplayed()
        assertEquals(track.id, app.library.playlistTracks(playlist.id).single().id)
        screenshot("local-playlist")
    }

    @Test fun aNewPlaylistFromTrackOptionsContainsThatTrack() {
        compose.onNodeWithTag("device-list").performScrollToNode(hasContentDescription("Options for Night Walk"))
        compose.onNodeWithContentDescription("Options for Night Walk").performClick()
        compose.onNodeWithText("Add to playlist").performScrollTo().performClick()
        // The playlist section behind the dialog has the same action, and the dialog's own button comes last.
        compose.onAllNodesWithText("New playlist").onLast().performClick()
        compose.onNodeWithText("Name").performTextInput("Evening")
        compose.onNodeWithText("Create", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { app.library.playlists().any { it.title == "Evening" && it.trackIds == listOf(track.id) } }
        val playlist = app.library.playlists().single { it.title == "Evening" }
        assertEquals(track.id, app.library.playlistTracks(playlist.id).single().id)
    }

    @Test fun removingAnImportedTrackClearsItsQueueAndPlaylistButKeepsTheFile() {
        val playlist = app.library.createPlaylist("Removal check")
        try {
            app.library.addToPlaylist(playlist.id, track)
            compose.runOnIdle { model.play(listOf(track, track)) }
            compose.waitUntil(10_000) { model.state.value.playback.queue.size == 2 }
            compose.runOnIdle { model.removeLocal(track) }
            compose.waitUntil(5_000) { model.state.value.local.none { it.id == track.id } && model.state.value.playback.queue.isEmpty() }
            assertTrue(app.library.playlistTracks(playlist.id).isEmpty())
            assertTrue(File(Uri.parse(track.localUri).path!!).isFile)
        } finally { app.library.deletePlaylist(playlist.id) }
    }

    @Test fun mainScreensAndThemeCanBeOpened() {
        screenshot("on-device")
        // One place needs no navigation, and its filter does the work of a search.
        compose.onAllNodesWithTag("nav-device").assertCountEquals(0)
        compose.onAllNodesWithTag("nav-search").assertCountEquals(0)
        compose.onNodeWithText("Filter").performTextInput("Night")
        compose.onNodeWithText("Night Walk").assertIsDisplayed()
        screenshot("filter-local")
        compose.onNodeWithText("Add files").assertIsDisplayed()
        compose.onNodeWithText("Add folder").assertIsDisplayed()
        screenshot("downloads")
        compose.onNodeWithContentDescription("Settings").performClick()
        screenshot("settings-dark")
    }

    @Test fun signedOutSettingsShowOnlyWhatWorksWithoutAnAccount() {
        compose.onNodeWithContentDescription("Settings").performClick()
        val list = compose.onNodeWithTag("settings-list")
        // Rows that change how TIDAL is streamed and downloaded need an account.
        for (hidden in listOf("Wi-Fi audio quality", "Mobile audio quality", "Autoplay", "Volume normalization", "Offline mode", "Download over mobile data", "Download saved tracks", "Download played tracks", "Audio output", "Keep saved tracks offline"))
            compose.onAllNodesWithText(hidden).assertCountEquals(0)
        // The update check can be turned off.
        list.performScrollToNode(hasText("Check for updates"))
        compose.onNode(hasText("Check for updates") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { !model.state.value.settings.checkUpdates }
        compose.onNode(hasText("Check for updates") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { model.state.value.settings.checkUpdates }
        // Experimental is a section of the list and not a page, so its rows show without a tap.
        list.performScrollToNode(hasText("Experimental"))
        list.performScrollToNode(hasText("Equalizer"))
        compose.onNodeWithText("Equalizer").assertIsDisplayed()
        list.performScrollToNode(hasText("ListenBrainz"))
        compose.onNodeWithText("ListenBrainz").assertIsDisplayed()
        list.performScrollToNode(hasText("Licenses"))
        compose.onNodeWithText("No ads, analytics or tracking. Streaming is provided by TIDAL.").assertIsDisplayed()
    }

    @Test fun theAvoidRepeatsPageListsTheExcludedTracksAndIncludesOneOnRequest() {
        app.library.clearHistory()
        app.library.recordPlay(track, 40_000)
        compose.runOnIdle { model.settings { it.copy(avoidRepeatHours = 24) } }
        try {
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Avoid repeats"))
            compose.onNode(hasText("Avoid repeats") and hasClickAction()).performClick()
            // Section labels are drawn in capitals.
            compose.onNodeWithText("AVOIDED TRACKS").assertIsDisplayed()
            compose.onNodeWithTag("repeats-list").performScrollToNode(hasText("Night Walk"))
            compose.onNodeWithText("Night Walk").assertIsDisplayed()
            compose.onNodeWithText("Allow").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("No avoided tracks").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(app.library.playedSince(0).isEmpty())
            // Every time window is one choice of several.
            compose.onNode(hasText("Off") and hasClickAction()).performClick()
            compose.waitUntil(5_000) { model.state.value.settings.avoidRepeatHours == 0 }
            compose.onAllNodesWithText("AVOIDED TRACKS").assertCountEquals(0)
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithTag("settings-list").assertIsDisplayed()
        } finally {
            compose.runOnIdle { model.settings { it.copy(avoidRepeatHours = 0) } }
            app.library.clearHistory()
        }
    }

    @Test fun theStatisticsPageSelectsOnePeriodAtATime() {
        app.library.clearHistory()
        app.library.recordPlay(track, 40_000)
        try {
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Listening statistics"))
            compose.onNode(hasText("Listening statistics") and hasClickAction()).performClick()
            compose.onNodeWithTag("stats-range").assertIsDisplayed()
            compose.onNode(hasText("7 days") and hasClickAction()).assertIsNotSelected()
            compose.onNode(hasText("30 days") and hasClickAction()).assertIsSelected()
            compose.onNode(hasText("7 days") and hasClickAction()).performClick()
            compose.onNode(hasText("7 days") and hasClickAction()).assertIsSelected()
            compose.onNode(hasText("30 days") and hasClickAction()).assertIsNotSelected()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Night Walk").fetchSemanticsNodes().isNotEmpty() }
        } finally { app.library.clearHistory() }
    }

    @Test fun thePermissionsPageFollowsTheFirstScreenAndEndsWithContinue() {
        org.junit.Assume.assumeTrue("A permission of this version of Android is still missing", io.github.yyan115.opentune.ui.Permissions.missing(app).isNotEmpty())
        try {
            compose.runOnIdle { model.settings { it.copy(setupPending = true) } }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("setup").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Permissions").assertIsDisplayed()
            compose.onNodeWithTag("setup-continue").performClick()
            compose.waitUntil(5_000) { !model.state.value.settings.setupPending }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("setup").fetchSemanticsNodes().isEmpty() }
        } finally { compose.runOnIdle { model.settings { it.copy(setupPending = false) } } }
    }

    @Test fun theLicencesOpenAsAListAndEachDocumentOnItsOwnPage() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Licenses"))
        compose.onNode(hasText("Licenses") and hasClickAction()).performClick()
        // One row for OpenTune and for each component that it ships, each with its licence.
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("licences-list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("OpenTune") and hasText("Apache License 2.0")).assertIsDisplayed()
        compose.onNodeWithTag("licences-list").performScrollToNode(hasText("OkHttp and Okio"))
        compose.onNodeWithText("Square · Apache License 2.0").assertIsDisplayed()
        compose.onNodeWithTag("licences-list").performScrollToNode(hasText("JetBrains Mono"))
        compose.onNodeWithText("JetBrains · SIL Open Font License 1.1").assertIsDisplayed()
        compose.onNodeWithTag("licences-list").performScrollToNode(hasText("OpenTune"))
        compose.onNode(hasText("OpenTune") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Version 2.0, January 2004", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("licences-list").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("settings-list").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 5_000)
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = (output?.let(::File) ?: File(app.getExternalFilesDir(null), "screenshots")).apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun queueRowsCanBeDraggedToANewPosition() {
        val first = track.copy(id = "local:drag-1", title = "First Track"); val second = track.copy(id = "local:drag-2", title = "Second Track"); val third = track.copy(id = "local:drag-3", title = "Third Track")
        compose.runOnIdle { model.play(listOf(first, second, third)) }
        compose.waitUntil(10_000) { model.state.value.playback.queue.size == 3 }
        compose.onNodeWithTag("mini-player").performClick()
        compose.onNodeWithText("Queue · 3").performClick()
        // A row is dragged by its handle past the row below it, then past the next one.
        fun dragFirstRowDown() = compose.onNodeWithContentDescription("Reorder First Track", useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, 12f)); advanceEventTime(16) }
            up()
        }
        dragFirstRowDown()
        compose.waitUntil(5_000) { model.state.value.playback.queue.map(Track::title) == listOf("Second Track", "First Track", "Third Track") }
        compose.waitForIdle()
        dragFirstRowDown()
        compose.waitUntil(5_000) { model.state.value.playback.queue.map(Track::title) == listOf("Second Track", "Third Track", "First Track") }
    }

    @Test fun shuffleReordersTheQueueAndTurningItOffRestoresTheOrder() {
        val tracks = (1..12).map { track.copy(id = "local:shuffle-$it", title = "Track $it") }
        compose.runOnIdle { model.play(tracks) }
        compose.waitUntil(10_000) { model.state.value.playback.queue.size == 12 }
        compose.runOnIdle { model.toggleShuffle() }
        compose.waitUntil(5_000) { model.state.value.playback.shuffle && model.state.value.playback.queue.map(Track::id) != tracks.map(Track::id) }
        val shuffled = model.state.value.playback.queue
        assertEquals("The current track stays first", tracks.first().id, shuffled.first().id)
        assertEquals("No track is lost or doubled", tracks.map(Track::id).toSet(), shuffled.map(Track::id).toSet())
        assertEquals(12, shuffled.size)
        // A track queued to play next plays next, also while shuffle is on.
        compose.runOnIdle { model.queue(track.copy(id = "local:next-up", title = "Next Up"), next = true) }
        compose.waitUntil(5_000) { model.state.value.playback.queue.size == 13 }
        assertEquals("Next Up", model.state.value.playback.queue[model.state.value.playback.index + 1].title)
        compose.runOnIdle { model.toggleShuffle() }
        compose.waitUntil(5_000) { !model.state.value.playback.shuffle && model.state.value.playback.queue.take(12).map(Track::id) == tracks.map(Track::id) }
        assertEquals("Next Up", model.state.value.playback.queue.last().title)
    }

    @Test fun aSwipeOnTheMiniPlayerStopsPlaybackAndATapOnlyOpensIt() {
        compose.runOnIdle { model.play(listOf(track, track.copy(id = "local:swipe-2", title = "Second Track"))) }
        compose.waitUntil(10_000) { model.state.value.playback.queue.size == 2 }
        compose.onNodeWithTag("mini-player").performClick()
        compose.onNodeWithText("Queue · 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close player").performClick()
        assertEquals("A tap leaves the queue alone", 2, model.state.value.playback.queue.size)
        compose.onNodeWithTag("mini-player").performTouchInput { swipeRight() }
        compose.waitUntil(5_000) { model.state.value.playback.queue.isEmpty() }
        compose.onNodeWithTag("mini-player").assertDoesNotExist()
    }

    @Test fun openingAnArtistFromThePlayerLeavesTheSettingsBehindIt() {
        // A downloaded track plays from the device and still belongs to TIDAL, so its artist has a page.
        val downloaded = track.copy(id = "4242", title = "Downloaded Track", artist = "Page Artist", artistId = "99", albumId = "98")
        app.library.save(downloaded)
        compose.runOnIdle { model.play(listOf(downloaded)) }
        compose.waitUntil(10_000) { model.state.value.playback.track?.id == downloaded.id }
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("settings-list").assertExists()
        compose.onNodeWithTag("mini-player").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("player-artist").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player-artist").performClick()
        compose.waitUntil(5_000) { model.state.value.routes.isNotEmpty() }
        compose.onNodeWithTag("settings-list").assertDoesNotExist()
        compose.onNodeWithTag("player-artist").assertDoesNotExist()
    }

    @Test fun theAnalyzerReadsTheSamplesThatTheOutputReceives() {
        io.github.yyan115.opentune.playback.AudioSpectrum.watch()
        try {
            io.github.yyan115.opentune.playback.AudioSpectrum.clear()
            compose.runOnIdle { model.play(listOf(track)) }
            compose.waitUntil(15_000) { model.state.value.playback.playing }
            val samples = FloatArray(io.github.yyan115.opentune.core.Spectrum.SIZE)
            val end = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < end && !io.github.yyan115.opentune.playback.AudioSpectrum.latest(samples)) Thread.sleep(100)
            assertTrue("The tap received no samples: ${io.github.yyan115.opentune.playback.AudioSpectrum}", io.github.yyan115.opentune.playback.AudioSpectrum.latest(samples))
            assertTrue("The tap sees the music, not silence", samples.any { kotlin.math.abs(it) > 0.001f })
            assertTrue("The signal path knows the decoded format", io.github.yyan115.opentune.playback.SignalPath.decoded != null)
            val bands = io.github.yyan115.opentune.core.Spectrum.bands(samples, io.github.yyan115.opentune.playback.AudioSpectrum.sampleRate)
            assertTrue("Some band carries energy", bands.any { it > 0.05f })
        } finally {
            io.github.yyan115.opentune.playback.AudioSpectrum.release()
            compose.runOnIdle { model.stopPlayback() }
        }
    }
}
