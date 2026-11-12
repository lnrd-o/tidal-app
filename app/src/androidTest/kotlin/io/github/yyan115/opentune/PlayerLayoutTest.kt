package io.github.yyan115.opentune

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.yyan115.opentune.core.Track
import io.github.yyan115.opentune.ui.MainViewModel
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The player's bottom row must sit directly above the system navigation area, whatever the screen and navigation style, also when a next track is queued. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlayerLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: MainViewModel
    private lateinit var track: Track
    private lateinit var next: Track

    @Before fun playOneTrack() {
        val app = compose.activity.application as OpenTuneApp
        model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnIdle { model.clearQueue(); model.settings { it.copy(offline = false, welcomeDone = true) } }
        val file = File(app.filesDir, "layout-test.flac")
        InstrumentationRegistry.getInstrumentation().context.assets.open("night-walk.flac").use { input -> file.outputStream().use(input::copyTo) }
        track = Track("local:layout-test", "Layout Check", "Test Artist", "Layout Album", durationMs = 30_000, localUri = Uri.fromFile(file).toString(), quality = "FLAC · 24-bit · 96 kHz")
        next = track.copy(id = "local:layout-next", title = "Layout Next")
        app.library.save(track); app.library.save(next)
        compose.waitUntil(10_000) { model.state.value.local.any { it.id == track.id } && model.state.value.local.any { it.id == next.id } }
        compose.runOnIdle { model.play(listOf(track, next)) }
        compose.waitUntil(30_000) { model.state.value.playback.track != null }
    }

    /** Screens from a small phone to a tall phone with a large display size, including the cover display of a Pixel Fold. */
    private val screens = listOf(
        Screen(1080, 2092, 420, "pixel-fold-cover"), Screen(1080, 2092, 480, "pixel-fold-cover-large"), Screen(1080, 2092, 560, "pixel-fold-cover-largest"),
        Screen(1344, 2992, 489, "pixel-8-pro"), Screen(1080, 2400, 420, "tall-phone"), Screen(720, 1280, 320, "small-phone"), Screen(1080, 1920, 480, "compact-phone"),
    )

    private data class Screen(val width: Int, val height: Int, val density: Int, val name: String)

    /**
     * Sets the display to [screen]. A display accepts at most twice its physical size on some system versions,
     * so a larger screen is set with fewer pixels and the same size in dp, which gives the same layout.
     */
    private fun applyDisplay(screen: Screen): Screen {
        shell("wm size ${screen.width}x${screen.height}")
        val (width, height) = displaySize()
        val applied = if (width == screen.width && height == screen.height) screen else {
            val scale = minOf(width.toDouble() / screen.width, height.toDouble() / screen.height)
            Screen((screen.width * scale).toInt(), (screen.height * scale).toInt(), (screen.density * scale).toInt(), screen.name).also { shell("wm size ${it.width}x${it.height}") }
        }
        shell("wm density ${applied.density}")
        return applied
    }

    /** The size that the display runs with now: the override when one is set, otherwise the physical size. */
    private fun displaySize(): Pair<Int, Int> {
        val line = shellOutput("wm size").lines().lastOrNull { it.contains("size:") } ?: throw AssertionError("The display size is unknown")
        val (width, height) = Regex("(\\d+)x(\\d+)").find(line)!!.destructured
        return width.toInt() to height.toInt()
    }

    @After fun restoreScreen() { shell("wm size reset"); shell("wm density reset") }

    @Test fun bottomRowSitsDirectlyAboveTheNavigationBarOnEveryScreen() {
        val failures = mutableListOf<String>()
        for (requested in screens) {
            val screen = applyDisplay(requested)
            // The system recreates the activity after a display change, and for a moment no screen exists.
            // A tap on the mini player during that moment is lost, so the tap repeats until the player shows.
            val queue = compose.onNodeWithText(compose.activity.getString(R.string.player_queue_count, 2))
            try {
                pollUntil("the app to use the new display") { resumedActivityUses(screen) }
                pollUntil("the player to show") {
                    if (!playerOpen() && compose.onAllNodesWithTag("mini-player").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("mini-player").performClick()
                    queue.isDisplayed()
                }
            } catch (failure: AssertionError) {
                val texts = runCatching { compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { root -> generateSequence(listOf(root)) { level -> level.flatMap { it.children }.takeIf { it.isNotEmpty() } }.flatten().toList() }
                    .flatMap { node -> listOfNotNull(node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text }) }.filter { it.isNotBlank() } }.getOrNull()
                screenshot("player-${screen.name}-failed")
                throw AssertionError("${screen.name} (${screen.width}x${screen.height} at ${screen.density} dpi): ${failure.message}. The activity reports ${activityDisplay()}. The screen shows: $texts", failure)
            }
            // The window of the player receives the system insets a moment after it opens, so the layout is measured until it has settled.
            var layout = measure(queue)
            val settleEnd = System.currentTimeMillis() + 10_000
            while (!layout.fits && System.currentTimeMillis() < settleEnd) { Thread.sleep(250); layout = runCatching { measure(queue) }.getOrDefault(layout) }
            screenshot("player-${screen.name}")
            if (layout.gap !in 0f..24f) failures += "${screen.name}: the bottom row ends ${"%.0f".format(layout.gap)} dp above the navigation bar (screen ${layout.screenHeight} px, navigation ${layout.navigation} px, row ends at ${layout.bottom} px)"
            if (!layout.titleClear) failures += "${screen.name}: the title overlaps or hides behind the controls (title ${layout.heading}, controls start at ${layout.transportTop}, screen ${layout.screenHeight})"
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            pollUntil("the player to close") { !playerOpen() }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** Where the bottom row and the title sit, as measured at one moment. */
    private class Layout(val gap: Float, val heading: androidx.compose.ui.geometry.Rect?, val transportTop: Float, val screenHeight: Int, val navigation: Int = 0, val bottom: Float = 0f) {
        val titleClear get() = heading != null && heading.top >= 0 && heading.bottom <= screenHeight && heading.bottom <= transportTop
        val fits get() = gap in 0f..24f && titleClear
    }

    private fun measure(queue: SemanticsNodeInteraction): Layout {
        compose.waitForIdle()
        // Some system versions leave the navigation bar out of the height that the resources report, so the whole display is measured.
        val metrics = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") compose.activity.windowManager.defaultDisplay.getRealMetrics(it) }
        val navigation = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val bottom = queue.fetchSemanticsNode().boundsInWindow.bottom
        // The title also appears on the screens behind the player, and all of them lie below the player's own title.
        val heading = compose.onAllNodesWithText("Layout Check").fetchSemanticsNodes().minByOrNull { it.boundsInWindow.top }?.boundsInWindow
        // The tracks are short, so the music can have ended by now. The button then says Play and not Pause, and it sits in the same place.
        val transportTop = compose.onAllNodes(hasContentDescription(compose.activity.getString(R.string.player_pause)) or hasContentDescription(compose.activity.getString(R.string.player_play))).fetchSemanticsNodes().minOf { it.boundsInWindow.top }
        return Layout((metrics.heightPixels - navigation - bottom) / metrics.density, heading, transportTop, metrics.heightPixels, navigation, bottom)
    }

    private fun pollUntil(what: String, timeoutMillis: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < end) {
            // The test clock advances only while the test synchronises, so a wait that only sleeps never shows work that finishes in the background.
            if (runCatching { compose.waitForIdle(); condition() }.getOrDefault(false)) return
            Thread.sleep(250)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    /** The size and density that the resumed activity runs with, or null while the system recreates it. */
    private fun activityDisplay(): String? {
        var result: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
                ?.resources?.displayMetrics?.let { "${it.widthPixels}x${it.heightPixels} at ${it.densityDpi} dpi" }
        }
        return result
    }

    private fun resumedActivityUses(screen: Screen) = activityDisplay().let { it != null && it.startsWith("${screen.width}x") && it.endsWith("at ${screen.density} dpi") }

    private fun playerOpen() = compose.onAllNodesWithText(compose.activity.getString(R.string.player_queue_count, 2)).fetchSemanticsNodes().isNotEmpty()

    private fun shell(command: String) { shellOutput(command) }

    private fun shellOutput(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { String(ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()) }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 5_000)
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = (output?.let(::File) ?: File(compose.activity.getExternalFilesDir(null), "screenshots")).apply { mkdirs() }
        // Some system versions return no screenshot while a window changes, so the view hierarchy is the fallback.
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: runCatching { compose.onRoot().captureToImage().asAndroidBitmap() }.getOrNull() ?: return
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
