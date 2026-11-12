package io.github.yyan115.opentune

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yyan115.opentune.core.Track
import io.github.yyan115.opentune.data.BackupManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@UnstableApi
@RunWith(AndroidJUnit4::class)
class BackupTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenTuneApp

    @Test fun playlistsHistoryAndSettingsSurviveABackupAndRestore() {
        val title = "Backup check"
        app.library.playlists().filter { it.title == title }.forEach { app.library.deletePlaylist(it.id) }
        val tidalTrack = Track("424242", "Streamed Song", "Some Artist", "Some Album", "9")
        val localTrack = Track("local:backup-check", "Imported", "Me", localUri = "content://example/imported.flac")
        val playlist = app.library.createPlaylist(title)
        app.library.addToPlaylist(playlist.id, tidalTrack); app.library.addToPlaylist(playlist.id, localTrack)
        app.library.recordPlay(tidalTrack, 90_000, 1_700_000_000_000)
        app.settings.update { it.copy(normalization = "album") }

        val backup = BackupManager(app).toJson()
        assertFalse("The backup must not contain tokens", backup.toString().contains("access", ignoreCase = true) || backup.toString().contains("refresh", ignoreCase = true))
        val text = backup.toString()

        app.library.deletePlaylist(playlist.id)
        app.settings.update { it.copy(normalization = "off") }
        val summary = BackupManager(app).restore(JSONObject(text))

        assertTrue(summary.playlists >= 1)
        val restored = app.library.playlists().single { it.title == title }
        assertEquals(listOf("424242"), restored.trackIds)
        assertEquals("album", app.settings.state.value.normalization)
        assertEquals(0, BackupManager(app).restore(JSONObject(text)).plays)
        assertEquals(1, app.library.playlists().count { it.title == title })
        app.library.deletePlaylist(restored.id)
        app.settings.update { it.copy(normalization = "off") }
    }

    @Test fun otherFilesAreRefused() {
        try { BackupManager(app).restore(JSONObject("""{"format":"something-else"}""")); fail() } catch (_: IllegalArgumentException) { }
        try { BackupManager(app).restore(JSONObject("""{"format":"opentune-backup","version":9}""")); fail() } catch (_: IllegalArgumentException) { }
    }
}
