package io.github.lnrdo.mytidal

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.lnrdo.mytidal.core.Track
import io.github.lnrdo.mytidal.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in checks of playlists and shuffle against a real account. They leave no playlist behind. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaylistsLiveTest {
    @get:Rule(order = 0) val account = LiveAccount.rule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    @Test fun songsAreAddedToAPlaylistFromItsOwnPage() {
        compose.runOnIdle { model.settings { it.copy(offline = false, welcomeDone = true) } }
        val title = "OpenTune check ${System.currentTimeMillis() % 100000}"
        compose.runOnIdle { model.createCloudPlaylist(title) }
        compose.waitUntil(30_000) { model.state.value.playlists.any { it.title == title } }
        val playlist = model.state.value.playlists.first { it.title == title }
        try {
            compose.runOnIdle { model.open(playlist) }
            compose.waitUntil(30_000) { model.state.value.detail?.id == playlist.id && !model.state.value.detailLoading }
            compose.onNodeWithTag("add-songs").performClick()
            compose.onNodeWithTag("add-songs-search").performTextInput("Norah Jones Don't Know Why")
            compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("Add Don't Know Why", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithContentDescription("Add Don't Know Why", substring = true).onFirst().performClick()
            compose.waitUntil(30_000) { model.state.value.detailTracks.any { it.title.startsWith("Don't Know Why") } }
            assertTrue(model.state.value.detailTracks.isNotEmpty())
            LiveAccount.screenshot("add-songs")
        } finally {
            compose.runOnIdle { model.deletePlaylist(playlist) }
            compose.waitUntil(30_000) { model.state.value.playlists.none { it.id == playlist.id } }
        }
    }

    @Test fun shuffleOnAStreamedQueueShowsAndPlaysTheSameOrder() {
        compose.runOnIdle { model.settings { it.copy(offline = false, welcomeDone = true) } }
        compose.runOnIdle { model.search("Norah Jones") }
        compose.waitUntil(30_000) { model.state.value.search.tracks.size >= 8 }
        val tracks: List<Track> = model.state.value.search.tracks.distinctBy(Track::id).take(10)
        compose.runOnIdle { model.play(tracks, 0, "check") }
        compose.waitUntil(30_000) { model.state.value.playback.queue.size == tracks.size && model.state.value.playback.playing }
        compose.runOnIdle { model.toggleShuffle() }
        compose.waitUntil(10_000) { model.state.value.playback.shuffle && model.state.value.playback.queue.map(Track::id) != tracks.map(Track::id) }
        val shown = model.state.value.playback.queue
        assertEquals("The track that plays stays where it is", tracks.first().id, shown.first().id)
        compose.runOnIdle { model.next() }
        compose.waitUntil(30_000) { model.state.value.playback.index == 1 }
        assertEquals("Next plays the track that the queue shows second", shown[1].id, model.state.value.playback.track?.id)
        compose.waitUntil(30_000) { model.state.value.playback.playing }
        compose.runOnIdle { model.stopPlayback() }
    }
}
