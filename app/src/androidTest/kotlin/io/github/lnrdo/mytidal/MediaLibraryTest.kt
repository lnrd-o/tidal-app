package io.github.lnrdo.mytidal

import android.content.ComponentName
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import io.github.lnrdo.mytidal.core.Track
import io.github.lnrdo.mytidal.playback.PlaybackService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Browses the library the way a car or a Bluetooth device does. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class MediaLibraryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as OpenTuneApp
    private lateinit var browser: MediaBrowser
    private lateinit var track: Track

    private fun <T> onMain(block: () -> ListenableFuture<T>): T {
        lateinit var future: ListenableFuture<T>
        val latch = CountDownLatch(1)
        instrumentation.runOnMainSync { future = block(); future.addListener({ latch.countDown() }, MoreExecutors.directExecutor()) }
        assertTrue("The media service did not answer", latch.await(20, TimeUnit.SECONDS))
        return future.get()
    }

    private fun <T : Any> androidx.media3.session.LibraryResult<T>.ok(): T { assertEquals("library result code (sessionError=${sessionError?.message})", androidx.media3.session.SessionResult.RESULT_SUCCESS, resultCode); return value!! }

    private fun <T> read(block: () -> T): T { var value: T? = null; instrumentation.runOnMainSync { value = block() }; @Suppress("UNCHECKED_CAST") return value as T }

    @Before fun connect() {
        val file = File(app.filesDir, "browse-test.flac")
        instrumentation.context.assets.open("night-walk.flac").use { input -> file.outputStream().use(input::copyTo) }
        track = Track("local:browse-test", "Night Walk", "Playback test", "Local audio checks", durationMs = 30_000, localUri = Uri.fromFile(file).toString())
        app.library.save(track); app.library.played(track)
        browser = onMain { MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
    }

    @After fun disconnect() { instrumentation.runOnMainSync { browser.release() } }

    @Test fun rootListsTheFoldersAndRecentTracksArePlayable() {
        val root = onMain { browser.getLibraryRoot(null) }.ok()
        assertEquals(context.getString(R.string.app_name), root.mediaMetadata.title.toString())
        val top = onMain { browser.getChildren(root.mediaId, 0, 50, null) }.ok()
        assertEquals(listOf("Recently played", "Saved tracks", "Albums", "Playlists", "On device"), top.map { it.mediaMetadata.title.toString() })
        assertTrue(top.all { it.mediaMetadata.isBrowsable == true })
        val recent = onMain { browser.getChildren("recent", 0, 50, null) }.ok()
        val item = recent.first { it.mediaId == track.id }
        assertEquals(true, item.mediaMetadata.isPlayable)
        assertEquals("Night Walk", item.mediaMetadata.title.toString())
        val downloads = onMain { browser.getChildren("downloads", 0, 50, null) }.ok()
        assertTrue(downloads.any { it.mediaId == track.id })
    }

    @Test fun searchFindsDeviceTracks() {
        val results = onMain { browser.getSearchResult("Night Wa", 0, 20, null) }.ok()
        assertTrue(results.any { it.mediaId == track.id })
    }

    @Test fun aTrackChosenInTheBrowserStartsPlaying() {
        val item = onMain { browser.getChildren("recent", 0, 50, null) }.ok().first { it.mediaId == track.id }
        instrumentation.runOnMainSync { browser.setMediaItem(item); browser.prepare(); browser.play() }
        val deadline = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < deadline && !read { browser.isPlaying }) Thread.sleep(250)
        assertTrue("Playback did not start", read { browser.isPlaying })
        assertEquals(track.id, read { browser.currentMediaItem?.mediaId })
        instrumentation.runOnMainSync { browser.pause() }
    }

    @Test fun unknownFoldersAreEmpty() {
        assertTrue(onMain { browser.getChildren("album:does-not-exist", 0, 10, null) }.ok().isEmpty())
    }
}
