package io.github.yyan115.opentune

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yyan115.opentune.core.Track
import io.github.yyan115.opentune.preview.ThemePreviewActivity
import io.github.yyan115.opentune.ui.MainViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the app in the en-XA pseudo-language, which wraps every string from a resource in square brackets and accents its letters.
 * Text that is still readable as plain English on a screen was written in code and cannot be translated.
 * Names of tracks and playlists, audio formats and the address of the source code come from data, so the test lists them as known.
 * One test walks the app as a listener without an account. The other walks the screens of a signed-in listener, which a debug-only
 * preview activity draws with sample music.
 */
@UnstableApi
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
@RunWith(AndroidJUnit4::class)
class PseudoLocaleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<Activity>? = null
    private lateinit var activity: Activity
    private lateinit var track: Track
    private val offenders = mutableListOf<String>()
    private val data = setOf("Night Walk", "Playback test", "Local audio checks", "Evening", "Night", "FLAC", "AAC", "@lnrd.o", io.github.yyan115.opentune.data.DownloadFiles.FOLDER) + ThemePreviewActivity.SAMPLE_TEXT
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val app get() = context as OpenTuneApp

    @Before fun prepare() {
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("en-XA")
        val deadline = System.currentTimeMillis() + 10_000
        while (app.resources.configuration.locales[0].toLanguageTag() != "en-XA" && System.currentTimeMillis() < deadline) Thread.sleep(100)
        val file = File(app.filesDir, "playback-test.flac")
        InstrumentationRegistry.getInstrumentation().context.assets.open("night-walk.flac").use { input -> file.outputStream().use(input::copyTo) }
        track = Track("local:playback-test", "Night Walk", "Playback test", "Local audio checks", durationMs = 30_000,
            localUri = Uri.fromFile(file).toString(), quality = "FLAC · 24-bit · 96 kHz")
        // Other tests leave titles in the library. They are data, so start from a library that holds only this test's data.
        app.library.localTracks().forEach { app.library.removeLocal(it.id) }
        app.library.playlists().forEach { app.library.deletePlaylist(it.id) }
        app.library.folders().forEach { app.library.removeFolder(it.id) }
        app.library.save(track)
        app.library.createPlaylist("Evening")
    }

    @After fun restoreLanguage() {
        scenario?.close()
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        app.library.playlists().filter { it.title == "Evening" }.forEach { app.library.deletePlaylist(it.id) }
        app.settings.update { it.copy(welcomeDone = true) }
    }

    private fun launch(intent: Intent) {
        scenario?.close()
        val started = ActivityScenario.launch<Activity>(intent)
        scenario = started
        started.onActivity { activity = it }
        assertEquals("The test needs the pseudo-language", "en-XA", activity.resources.configuration.locales[0].toLanguageTag())
    }

    private fun launchApp(): MainViewModel {
        launch(Intent(context, MainActivity::class.java))
        return ViewModelProvider(activity as ComponentActivity)[MainViewModel::class.java]
    }

    private fun launchPreview(page: String, vararg extras: Pair<String, Any>) = launch(Intent().setClassName(context.packageName, "io.github.yyan115.opentune.preview.ThemePreviewActivity")
        .putExtra("page", page).apply { extras.forEach { (key, value) -> if (value is Boolean) putExtra(key, value) } })

    private fun s(@StringRes id: Int, vararg args: Any): String = activity.getString(id, *args)

    @Test fun everyScreenOfTheLocalModeComesFromResources() {
        app.settings.update { it.copy(welcomeDone = false, offline = false) }
        val model = launchApp()
        compose.runOnIdle { model.clearQueue() }
        await("the track in the library") { model.state.value.local.any { it.id == track.id } }
        assertTrue("The resource text must carry the pseudo-language marks", s(R.string.nav_home).startsWith("["))
        await("the welcome screen") { compose.onAllNodesWithTag("welcome").fetchSemanticsNodes().isNotEmpty() }
        sweep("Welcome")
        compose.onNodeWithTag("welcome-local").performClick()
        // The permissions page follows the first screen when a permission of this version of Android is missing.
        if (io.github.yyan115.opentune.ui.Permissions.missing(activity).isNotEmpty()) {
            await("the permissions page") { compose.onAllNodesWithTag("setup").fetchSemanticsNodes().isNotEmpty() }
            sweep("Permissions")
            compose.onNodeWithTag("setup-continue").performClick()
        }
        await("the On device screen") { compose.onAllNodesWithTag("device-list").fetchSemanticsNodes().isNotEmpty() }
        sweep("On device")
        inWindow("New playlist") {
            compose.onNodeWithTag("device-list").performScrollToNode(hasText(s(R.string.playlist_new)))
            compose.onNode(hasText(s(R.string.playlist_new)) and hasClickAction()).performClick()
        }
        inWindow("Track options") {
            compose.onNodeWithTag("device-list").performScrollToNode(hasContentDescription(s(R.string.track_options, "Night Walk")))
            compose.onAllNodesWithContentDescription(s(R.string.track_options, "Night Walk")).onFirst().performClick()
        }
        for ((row, name) in listOf(R.string.track_details to "Track details", R.string.playlist_add to "Add to playlist")) inWindow(name) {
            compose.onNodeWithTag("device-list").performScrollToNode(hasContentDescription(s(R.string.track_options, "Night Walk")))
            compose.onAllNodesWithContentDescription(s(R.string.track_options, "Night Walk")).onFirst().performClick()
            compose.onNode(hasText(s(row)) and hasClickAction()).performClick()
        }
        for (filter in listOf(R.string.device_filter_downloads, R.string.device_filter_files, R.string.device_filter_all)) {
            compose.onNodeWithTag("device-list").performScrollToNode(hasText(s(filter)))
            compose.onAllNodes(hasText(s(filter)) and hasClickAction()).onFirst().performClick()
            sweep("On device ${s(filter)}")
        }


        compose.runOnIdle { model.play(listOf(track)) }
        await("playback to start", 30_000) { model.state.value.playback.playing || model.state.value.playback.error != null }
        val screen = openWindow("Player") { compose.onNodeWithTag("mini-player").performClick() }
        sweep("Player")
        inWindow("Queue") { compose.onNodeWithText(s(R.string.player_queue_count, 1)).scrollAndClick() }
        inWindow("Sleep timer") { compose.onNodeWithContentDescription(s(R.string.player_timer_set)).scrollAndClick() }
        inWindow("Source details") { compose.onNodeWithContentDescription(s(R.string.player_source_details)).scrollAndClick() }
        closeWindow(screen)
        compose.runOnIdle { model.clearQueue() }

        compose.onNodeWithContentDescription(s(R.string.nav_settings)).performClick()
        val list = compose.onNodeWithTag("settings-list")
        sweep("Settings top")
        list.performScrollToNode(hasText(s(R.string.settings_data)))
        sweep("Settings middle")
        list.performScrollToNode(hasText(s(R.string.settings_experimental)))
        sweep("Settings experimental")
        list.performScrollToNode(hasText(s(R.string.settings_licenses)))
        sweep("Settings bottom")
        for ((row, name) in settingsDialogs()) {
            list.performScrollToNode(hasText(s(row)))
            inWindow(name) { compose.onAllNodes(hasText(s(row)) and hasClickAction()).onFirst().performClick() }
        }
        // The pages that open from the list. The avoid-repeats page also shows its list of excluded tracks.
        for ((row, name) in listOf(R.string.settings_repeats to "Avoid repeats page", R.string.stats_title to "Statistics page", R.string.settings_licenses to "Licenses page")) {
            if (row == R.string.settings_repeats) app.settings.update { it.copy(avoidRepeatHours = 24) }
            list.performScrollToNode(hasText(s(row)))
            compose.onAllNodes(hasText(s(row)) and hasClickAction()).onFirst().performClick()
            await("the $name") { compose.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isEmpty() }
            // The licence list and the licence texts come from the notices files and stay in English, so they are only checked for opening.
            if (row != R.string.settings_licenses) sweep(name)
            if (row == R.string.settings_licenses) {
                compose.onAllNodes(hasText(s(R.string.brand_name)) and hasClickAction()).onFirst().performClick()
                await("the licence document") { compose.onAllNodesWithTag("licence-document").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription(s(R.string.action_back)).performClick()
                await("the licence list") { compose.onAllNodesWithTag("licences-list").fetchSemanticsNodes().isNotEmpty() }
            }
            compose.onNodeWithContentDescription(s(R.string.action_back)).performClick()
            await("the settings list") { compose.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
            if (row == R.string.settings_repeats) app.settings.update { it.copy(avoidRepeatHours = 0) }
        }
        finish()
    }

    @Test fun everyScreenOfASignedInListenerComesFromResources() {
        launchPreview("home"); await("Home") { compose.onAllNodesWithTag("nav-home").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("The resource text must carry the pseudo-language marks", s(R.string.nav_home).startsWith("["))
        sweep("Home")
        launchPreview("search"); await("Search") { compose.onAllNodesWithTag("nav-search").fetchSemanticsNodes().isNotEmpty() }
        sweep("Search with a query")
        launchPreview("library"); await("Library") { compose.onAllNodesWithTag("library-filters").fetchSemanticsNodes().isNotEmpty() }
        for (filter in listOf(R.string.library_saved_tracks, R.string.label_albums, R.string.label_artists, R.string.label_playlists, R.string.library_mixes)) {
            compose.onNodeWithTag("library-filters").performScrollToNode(hasText(s(filter)))
            compose.onNode(hasText(s(filter)) and hasClickAction()).performClick()
            sweep("Library ${s(filter)}")
        }
        launchPreview("device"); await("On device") { compose.onAllNodesWithTag("device-list").fetchSemanticsNodes().isNotEmpty() }
        sweep("On device when signed in")
        launchPreview("album"); await("Album") { compose.onAllNodesWithText("Night Walk").fetchSemanticsNodes().isNotEmpty() }
        sweep("Album")
        launchPreview("player", "lyrics" to true); await("Player") { compose.onAllNodesWithText(s(R.string.player_queue_count, 4)).fetchSemanticsNodes().isNotEmpty() }
        sweep("Player with lyrics")
        launchPreview("settings"); await("Settings") { compose.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
        sweep("Settings when signed in")
        // The rows that need an account, with the dialogs that they open.
        val signedInList = compose.onNodeWithTag("settings-list")
        for ((row, name) in listOf(R.string.settings_quality_wifi to "Wi-Fi quality", R.string.settings_quality_mobile to "Mobile quality", R.string.normalization_title to "Normalization",
            R.string.settings_download_played to "Download played tracks", R.string.settings_mobile_downloads to "Download over mobile data")) {
            signedInList.performScrollToNode(hasText(s(row)))
            inWindow(name) { compose.onAllNodes(hasText(s(row)) and hasClickAction()).onFirst().performClick() }
        }
        signedInList.performScrollToNode(hasText(s(R.string.settings_connected)))
        inWindow("Disconnect") { compose.onAllNodes(hasText(s(R.string.settings_connected)) and hasClickAction()).onFirst().performClick() }
        finish()
    }

    private fun settingsDialogs() = listOf(
        R.string.scrobble_title to "Scrobbling",
        R.string.settings_clear_history to "Clear history",
    )

    private fun finish() {
        val report = offenders.joinToString("\n")
        assertTrue("Text written in code, which cannot be translated:\n$report", offenders.isEmpty())
    }

    /** The player scrolls on a small screen, so a button can sit below the visible part. */
    private fun SemanticsNodeInteraction.scrollAndClick() {
        runCatching { performScrollTo() }
        performClick()
    }

    /**
     * Waits until [condition] holds. The system recreates activities while a test runs, and for a moment no screen exists, so a failed check counts as not yet.
     * A timeout reports what is on the screen and saves a screenshot, which a bare timeout does not show.
     */
    private fun await(what: String, timeoutMillis: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMillis
        var lastFailure: Throwable? = null
        while (System.currentTimeMillis() < end) {
            // The test clock advances only while the test synchronises, so a wait that only sleeps never shows work that finishes in the background.
            if (runCatching { compose.waitForIdle(); condition() }.onFailure { lastFailure = it }.getOrDefault(false)) return
            Thread.sleep(200)
        }
        val screen = runCatching { visibleTexts() }.getOrNull()
        runCatching { screenshot("pseudo-timeout") }
        throw AssertionError("Timed out waiting for $what. Windows: ${runCatching { windows() }.getOrNull()}. The last failure was: $lastFailure. The screen shows: $screen", lastFailure)
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = (output?.let(::File) ?: File(activity.getExternalFilesDir(null), "screenshots")).apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun windows() = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().size

    /** Opens a dialog, sheet or screen that has a window of its own, checks it and closes it again. */
    private fun inWindow(name: String, check: () -> Unit = { sweep(name) }, open: () -> Unit) {
        val base = openWindow(name, open)
        check()
        closeWindow(base)
    }

    /** Runs [open] and waits for the new window. Returns the number of windows from before, which [closeWindow] restores. */
    private fun openWindow(name: String, open: () -> Unit): Int {
        val base = windows()
        open()
        await("the window \"$name\" to open") { windows() > base }
        return base
    }

    /**
     * Closes windows with the back key until [base] windows remain. Back only reaches a window that has the focus,
     * which a slow device gives late, so the key is sent again until the window is gone.
     */
    private fun closeWindow(base: Int) {
        repeat(5) {
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            compose.waitForIdle()
            val deadline = System.currentTimeMillis() + 2_000
            while (windows() > base && System.currentTimeMillis() < deadline) { Thread.sleep(100); compose.waitForIdle() }
            if (windows() <= base) return
        }
        throw AssertionError("The window did not close")
    }

    /** Collects the texts and descriptions that a screen shows and reports the ones that no resource wrapped. */
    private fun sweep(screen: String) {
        compose.waitForIdle()
        val systemText = systemTexts()
        val texts = visibleTexts()
        texts.filter { text -> !isPseudo(text) && text !in data && text !in systemText && letters(text, systemText) }
            .forEach { offenders += "$screen: \"$it\"" }
    }

    private fun visibleTexts(): Set<String> {
        val texts = linkedSetOf<String>()
        fun visit(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { texts += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { texts += it }
            node.children.forEach(::visit)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::visit)
        return texts
    }

    private fun isPseudo(text: String) = text.startsWith("[") && text.endsWith("]")

    private fun letters(text: String, systemText: Set<String>): Boolean {
        var rest = text.replace(Regex("\\[[^\\]]*]"), "")
        (data + systemText).sortedByDescending { it.length }.forEach { rest = rest.replace(it, "") }
        return Regex("[A-Za-z]{2,}").containsMatchIn(rest)
    }

    /** Names that Android supplies, such as the audio devices and the presets of the equalizer. */
    private fun systemTexts(): Set<String> = buildSet {
        val devices = activity.getSystemService(android.media.AudioManager::class.java).getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        devices.forEach { it.productName?.toString()?.let(::add) }
        add(activity.packageName)
    }
}
