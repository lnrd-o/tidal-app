package io.github.yyan115.opentune

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import io.github.yyan115.opentune.core.TidalClient
import io.github.yyan115.opentune.ui.MainViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in check with a real account: playlist covers that fail while the device is offline load by themselves when the network returns. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class LibraryArtworkLiveTest {
    @get:Rule(order = 0) val account = LiveAccount.rule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }

    /** Lets the screen run for the given time. A plain sleep would stop the clock that drives the screen in a test. */
    private fun pump(milliseconds: Long) {
        val end = System.currentTimeMillis() + milliseconds
        while (System.currentTimeMillis() < end) { compose.waitForIdle(); Thread.sleep(200) }
    }

    @Test fun coversLoadByThemselvesWhenTheNetworkReturns() {
        compose.runOnIdle { model.settings { it.copy(offline = false) } }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SingletonImageLoader.get(context).let { loader -> loader.diskCache?.clear(); loader.memoryCache?.clear() }
        try {
            shell("svc wifi disable"); shell("svc data disable")
            pump(3_000)
            compose.onNodeWithTag("nav-library").performClick()
            val label = compose.activity.getString(R.string.label_playlists)
            compose.onNodeWithTag("library-filters").performScrollToNode(hasText(label))
            compose.onAllNodes(hasText(label) and hasClickAction()).onFirst().performClick()
            compose.waitUntil(30_000) { model.state.value.playlists.isNotEmpty() || !model.state.value.folders[TidalClient.ROOT_FOLDER].isNullOrEmpty() }
            pump(6_000)
        } finally { shell("svc wifi enable"); shell("svc data enable") }
        val urls = (model.state.value.folders[TidalClient.ROOT_FOLDER].orEmpty().ifEmpty { model.state.value.playlists }).mapNotNull { it.artwork }.take(3)
        assertTrue("The test account needs playlists with covers", urls.isNotEmpty())
        val loader = SingletonImageLoader.get(context)
        val end = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < end && !urls.all { url -> loader.memoryCache?.keys?.any { it.key.contains(url) } == true }) { compose.waitForIdle(); Thread.sleep(500) }
        val missing = urls.filter { url -> loader.memoryCache?.keys?.any { it.key.contains(url) } != true }
        assertTrue("Covers still missing 60 s after the network returned: ${missing.map { it.takeLast(30) }}", missing.isEmpty())
    }
}
