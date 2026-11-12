package io.github.yyan115.opentune

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yyan115.opentune.core.Track
import io.github.yyan115.opentune.ui.MainViewModel
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Walks the screens that a listener without an account sees and saves a picture of each one.
 * It is a tool for reviewing the interface and runs only with the instrumentation argument `screenTour` set to `true`.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ScreenTourTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as OpenTuneApp
    private val model get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    @Before fun onlyWhenAsked() { assumeTrue(InstrumentationRegistry.getArguments().getString("screenTour") == "true") }

    private fun picture(name: String) {
        compose.waitForIdle(); Thread.sleep(600); compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 5_000)
        val directory = File(compose.activity.getExternalFilesDir(null), "tour").apply { mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun tour() {
        compose.runOnIdle { model.clearQueue(); model.settings { it.copy(offline = false, welcomeDone = true) } }
        val file = File(app.filesDir, "tour.flac")
        InstrumentationRegistry.getInstrumentation().context.assets.open("night-walk.flac").use { input -> file.outputStream().use(input::copyTo) }
        val tracks = listOf("Night Walk" to "Local audio checks", "Glass Rooms" to "Local audio checks", "Slow Tide" to "Salt and Glass").mapIndexed { index, (title, album) ->
            Track("local:tour-$index", title, if (index < 2) "Harbour Lights" else "Maren Vale", album, durationMs = 30_000, localUri = Uri.fromFile(file).toString(), quality = "FLAC · 24-bit · 96.0 kHz · 2304 kbps")
        }
        tracks.forEach(app.library::save)
        app.library.playlists().filter { it.title == "Evening" }.forEach { app.library.deletePlaylist(it.id) }
        val playlist = app.library.createPlaylist("Evening"); app.library.addToPlaylist(playlist.id, tracks[0])
        compose.waitUntil(10_000) { model.state.value.local.size >= 3 && model.state.value.localPlaylists.any { it.title == "Evening" } }
        picture("1-on-device")
        compose.onNodeWithTag("device-list").performScrollToNode(hasContentDescription("Options for Night Walk"))
        compose.onAllNodesWithContentDescription("Options for Night Walk").onFirst().performClick()
        picture("2-track-menu")
        compose.onNode(hasText("Details") and hasClickAction()).performClick()
        picture("3-details")
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithTag("device-list").performScrollToNode(hasText("Evening"))
        compose.onNodeWithText("Evening").performClick()
        picture("4-playlist")
        compose.onNodeWithTag("add-songs").performClick()
        picture("5-add-songs")
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { model.play(tracks) }
        compose.waitUntil(15_000) { model.state.value.playback.queue.size == 3 }
        compose.onNodeWithTag("mini-player").performClick()
        picture("6-player")
        compose.onNodeWithText("Queue · 3").performClick()
        picture("7-queue")
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { model.stopPlayback() }
        compose.onNodeWithContentDescription("Settings").performClick()
        picture("8-settings")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Experimental"))
        picture("9-experimental")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Licenses"))
        compose.onNode(hasText("Licenses") and hasClickAction()).performClick()
        picture("11-licences")
    }

    @Test fun settingsAndFirstScreen() {
        compose.runOnIdle { model.clearQueue(); model.settings { it.copy(offline = false, welcomeDone = false, avoidRepeatHours = 0) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("welcome").fetchSemanticsNodes().isNotEmpty() }
        picture("20-first-screen")
        compose.runOnIdle { model.settings { it.copy(welcomeDone = true) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("device-list").fetchSemanticsNodes().isNotEmpty() }
        picture("21-on-device")
        compose.onNodeWithContentDescription("Settings").performClick()
        picture("22-settings-top")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Licenses"))
        picture("23-settings-about")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Listening statistics"))
        compose.onNode(hasText("Listening statistics") and hasClickAction()).performClick()
        app.library.clearHistory()
        picture("24-statistics-empty")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Avoid repeats"))
        compose.runOnIdle { model.settings { it.copy(avoidRepeatHours = 24) } }
        compose.onNode(hasText("Avoid repeats") and hasClickAction()).performClick()
        picture("25-avoid-repeats")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { model.settings { it.copy(avoidRepeatHours = 0) } }
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("Licenses"))
        compose.onNode(hasText("Licenses") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("licences-list").fetchSemanticsNodes().isNotEmpty() }
        picture("26-licences")
        compose.onNodeWithContentDescription("Back").performClick()
    }

    @Test fun emptyDeviceList() {
        compose.runOnIdle { model.clearQueue(); model.settings { it.copy(welcomeDone = true) } }
        app.library.localTracks().forEach { app.library.removeLocal(it.id) }
        app.library.playlists().forEach { app.library.deletePlaylist(it.id) }
        compose.waitUntil(10_000) { model.state.value.local.isEmpty() && model.state.value.localPlaylists.isEmpty() }
        picture("30-empty-device")
    }

    @Test fun permissionsPage() {
        assumeTrue("A permission of this version of Android is still missing", io.github.yyan115.opentune.ui.Permissions.missing(app).isNotEmpty())
        compose.runOnIdle { model.settings { it.copy(welcomeDone = true, setupPending = true) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("setup").fetchSemanticsNodes().isNotEmpty() }
        picture("31-permissions")
        compose.runOnIdle { model.settings { it.copy(setupPending = false) } }
    }
}
