package io.github.yyan115.opentune

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yyan115.opentune.core.*
import io.github.yyan115.opentune.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in checks of the TIDAL pages, lyrics and credits against a real account. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PagesLiveTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenTuneApp
    @get:Rule(order = 0) val account = LiveAccount.rule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]
    private val pages get() = ViewModelProvider(compose.activity)[PagesViewModel::class.java]

    private fun settle(milliseconds: Long = 1500) { compose.waitForIdle(); Thread.sleep(milliseconds); compose.waitForIdle() }

    @Test fun listenTabShowsPersonalisedAndEditorialSections() {
        compose.runOnIdle { model.settings { it.copy(offline = false) } }
        compose.waitUntil(45_000) { pages.state.value[PageRoute.ForYou.key]?.page != null && pages.state.value[PageRoute.Home.key]?.page != null }
        val forYou = pages.state.value.getValue(PageRoute.ForYou.key).page!!
        assertTrue("For you has mixes", forYou.modules.any { it is CarouselModule && it.layout == CarouselLayout.MIXES })
        assertTrue("Home has tracks or albums", pages.state.value.getValue(PageRoute.Home.key).page!!.modules.any { it is TrackListModule || it is CarouselModule })
        settle(); LiveAccount.screenshot("listen-top")
        compose.onNodeWithTag("nav-search").performClick()
        compose.waitUntil(30_000) { pages.state.value[PageRoute.Explore.key]?.page != null }
        assertTrue(pages.state.value.getValue(PageRoute.Explore.key).page!!.modules.any { it is LinksModule && it.cloud })
        settle(); LiveAccount.screenshot("search-explore")
    }

    @Test fun thePlayerTitleAndArtistOpenTheAlbumAndTheArtistPage() {
        compose.runOnIdle { model.settings { it.copy(offline = false) } }
        compose.runOnIdle { model.search("Norah Jones Don't Know Why") }
        compose.waitUntil(30_000) { model.state.value.search.tracks.isNotEmpty() }
        val track = model.state.value.search.tracks.first { it.title.startsWith("Don't Know Why") }
        compose.runOnIdle { model.play(listOf(track)) }
        compose.waitUntil(30_000) { model.state.value.playback.track != null }

        compose.onNodeWithTag("mini-player").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("player-artist").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player-artist").performClick()
        val artistKey = PageRoute.artist(track.artistId).key
        compose.waitUntil(30_000) { pages.state.value[artistKey]?.page != null }
        assertEquals("The artist page is open", artistKey, model.state.value.routes.lastOrNull()?.key)
        assertTrue("The player closes behind the page", compose.onAllNodesWithTag("player-artist").fetchSemanticsNodes().isEmpty())
        settle(); LiveAccount.screenshot("player-artist-link")

        compose.runOnIdle { model.closeAll() }
        compose.onNodeWithTag("mini-player").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("player-title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("player-title").performClick()
        val albumKey = PageRoute.album(track.albumId).key
        compose.waitUntil(30_000) { pages.state.value[albumKey]?.page != null }
        assertEquals("The album page is open", albumKey, model.state.value.routes.lastOrNull()?.key)
        settle(); LiveAccount.screenshot("player-album-link")
    }

    @Test fun albumArtistCreditsAndLyricsWork() {
        compose.runOnIdle { model.settings { it.copy(offline = false) } }
        compose.runOnIdle { model.search("Norah Jones Don't Know Why") }
        compose.waitUntil(30_000) { model.state.value.search.tracks.isNotEmpty() }
        val track = model.state.value.search.tracks.first { it.title.startsWith("Don't Know Why") }
        compose.onNodeWithTag("nav-search").performClick()
        settle(); LiveAccount.screenshot("search-results")

        compose.runOnIdle { model.open(Release(track.albumId, track.album, track.artist, type = ReleaseType.ALBUM)) }
        val albumKey = PageRoute.album(track.albumId).key
        compose.waitUntil(30_000) { pages.state.value[albumKey]?.page != null }
        val album = pages.state.value.getValue(albumKey).page!!
        assertTrue(album.modules.any { it is AlbumHeader && it.review.isNotBlank() })
        assertTrue(album.modules.filterIsInstance<TrackListModule>().first().numbered)
        settle(); LiveAccount.screenshot("album-page")

        compose.runOnIdle { model.showCredits("album", track.albumId, track.album) }
        compose.waitUntil(30_000) { model.state.value.credits?.loading == false }
        assertTrue(model.state.value.credits!!.credits.isNotEmpty())
        settle(); LiveAccount.screenshot("album-credits")
        compose.runOnIdle { model.dismissCredits() }

        compose.runOnIdle { model.closeAll(); model.open(Release(track.artistId, "Norah Jones", "", type = ReleaseType.ARTIST)) }
        val artistKey = PageRoute.artist(track.artistId).key
        compose.waitUntil(30_000) { pages.state.value[artistKey]?.page != null }
        val artist = pages.state.value.getValue(artistKey).page!!
        assertTrue(artist.modules.any { it is ArtistHeader && it.bio.isNotBlank() && !it.bio.contains("wimpLink") })
        settle(); LiveAccount.screenshot("artist-page")

        // This track has timed lyrics. Norah Jones' track above has none, which is covered by the unit tests.
        val lyricTrack = Track("571481561", "Feather", "Nujabes")
        compose.runOnIdle { model.closeAll(); model.play(listOf(lyricTrack)) }
        compose.waitUntil(30_000) { model.state.value.playback.playing || model.state.value.playback.error != null }
        assertNull(model.state.value.playback.error)
        compose.onNodeWithTag("mini-player").performClick()
        settle()
        compose.onNodeWithText("Lyrics").performClick()
        compose.waitUntil(30_000) { model.state.value.lyricsStatus == LyricsStatus.Ready }
        val lyrics = model.state.value.lyrics!!
        assertTrue("Lyrics have timing", lyrics.synced)
        val index = lyrics.lines.size / 2
        compose.runOnIdle { model.seek(lyrics.lines[index].timeMs + 400) }
        compose.waitUntil(15_000) { model.position.value >= lyrics.lines[index].timeMs }
        settle(2500); LiveAccount.screenshot("player-lyrics")
        compose.runOnIdle { model.togglePlay() }
    }

    @Test fun musicVideoPlaysFromAnArtistPage() {
        compose.runOnIdle { model.settings { it.copy(offline = false) } }
        val key = PageRoute.artist("10249").key
        compose.runOnIdle { model.open(Release("10249", "Norah Jones", "", type = ReleaseType.ARTIST)) }
        compose.waitUntil(30_000) { pages.state.value[key]?.page != null }
        val video = pages.state.value.getValue(key).page!!.modules.filterIsInstance<CarouselModule>().first { it.layout == CarouselLayout.VIDEOS }.items.first()
        compose.runOnIdle { model.open(video) }
        try { compose.waitUntil(60_000) { compose.onAllNodesWithContentDescription("Pause video").fetchSemanticsNodes().isNotEmpty() } }
        catch (timeout: Throwable) { LiveAccount.screenshot("video-timeout"); throw timeout }
        fun videoPosition() = Regex("position=(\\d+)").find(compose.onNodeWithTag("video-player").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription])!!.groupValues[1].toLong()
        try { compose.waitUntil(30_000) { videoPosition() > 3_000 } }
        catch (timeout: Throwable) { LiveAccount.screenshot("video-stalled"); throw timeout }
        compose.onNodeWithTag("video-player").performClick()
        compose.waitForIdle(); Thread.sleep(500)
        LiveAccount.screenshot("video-playing")
        compose.runOnIdle { model.closeVideo() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("video-player").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun libraryShowsAFolderWithItsPlaylistAndOpensIt() {
        compose.runOnIdle { model.settings { it.copy(offline = false) } }
        val suffix = java.util.UUID.randomUUID().toString().take(6)
        val folderName = "Check folder $suffix"; val playlistName = "Check playlist $suffix"
        val folder = kotlinx.coroutines.runBlocking { app.tidal.createFolder(folderName) }
        val playlist = kotlinx.coroutines.runBlocking { app.tidal.createPlaylist(playlistName) }
        try {
            kotlinx.coroutines.runBlocking { app.tidal.moveToFolder(playlist.id, folder.id) }
            compose.runOnIdle { model.loadFolder() }
            compose.waitUntil(30_000) { model.state.value.folders[io.github.yyan115.opentune.core.TidalClient.ROOT_FOLDER].orEmpty().any { it.id == folder.id } }
            compose.onNodeWithTag("nav-library").performClick()
            compose.onNodeWithTag("library-filters").performScrollToNode(androidx.compose.ui.test.hasText("Playlists"))
            compose.onNodeWithText("Playlists").performClick()
            compose.onNodeWithText(folderName).performScrollTo()
            settle(); LiveAccount.screenshot("library-folders")
            compose.onNodeWithText(folderName).performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithText(playlistName).fetchSemanticsNodes().isNotEmpty() }
            settle(); LiveAccount.screenshot("library-folder-open")
        } finally {
            kotlinx.coroutines.runBlocking {
                runCatching { app.tidal.deletePlaylist(playlist.id) }
                runCatching { app.tidal.removeFolder(folder.id) }
            }
            compose.runOnIdle { model.loadFolder() }
        }
    }

    @Test fun mixPageAndTrackRadioPlay() {
        compose.runOnIdle { model.settings { it.copy(offline = false) } }
        compose.waitUntil(45_000) { pages.state.value[PageRoute.ForYou.key]?.page != null || run { pages.load(PageRoute.ForYou); false } }
        val mix = pages.state.value.getValue(PageRoute.ForYou.key).page!!.modules.filterIsInstance<CarouselModule>().first { it.layout == CarouselLayout.MIXES }.items.first()
        compose.runOnIdle { model.open(mix) }
        val key = PageRoute.mix(mix.id).key
        compose.waitUntil(30_000) { pages.state.value[key]?.page != null }
        val tracks = pages.state.value.getValue(key).page!!.modules.filterIsInstance<TrackListModule>().first().tracks
        assertTrue(tracks.isNotEmpty())
        settle(); LiveAccount.screenshot("mix-page")
        compose.runOnIdle { model.playTrackRadio(tracks.first()) }
        compose.waitUntil(30_000) { model.state.value.playback.queue.size > 10 }
        compose.runOnIdle { model.togglePlay() }
    }
}
