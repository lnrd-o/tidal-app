package io.github.lnrdo.mytidal

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lnrdo.mytidal.core.DocumentKey
import io.github.lnrdo.mytidal.core.JsonCodec
import io.github.lnrdo.mytidal.core.Track
import io.github.lnrdo.mytidal.data.LibraryStore
import io.github.lnrdo.mytidal.data.LocalSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private inline fun withStore(name: String, block: (LibraryStore) -> Unit) {
        val store = LibraryStore(context, name)
        try { block(store) } finally { store.close() }
    }

    @Test fun upgradesExistingLibraryWithoutLosingTracksOrPlaylists() {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val track = Track("local:migration", "Original track", "Original artist", localUri = "content://original/audio/1")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE tracks (id TEXT PRIMARY KEY, payload TEXT NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0)")
            old.execSQL("CREATE TABLE playlists (id TEXT PRIMARY KEY, title TEXT NOT NULL)")
            old.execSQL("CREATE TABLE playlist_tracks (playlist_id TEXT NOT NULL, track_id TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(playlist_id,track_id), FOREIGN KEY(playlist_id) REFERENCES playlists(id) ON DELETE CASCADE)")
            old.execSQL("INSERT INTO tracks VALUES (?,?,1,123)", arrayOf(track.id, JsonCodec.encodeTrack(track).toString()))
            old.execSQL("INSERT INTO playlists VALUES ('local:existing','Existing playlist')")
            old.execSQL("INSERT INTO playlist_tracks VALUES ('local:existing',?,0)", arrayOf(track.id))
            old.version = 1
        }
        try { withStore(name) { store ->
            assertEquals(track, store.get(track.id))
            assertEquals(track.id, store.favorites().single().id)
            assertEquals(track.id, store.recent().single().id)
            assertEquals(track.id, store.playlistTracks("local:existing").single().id)
            store.cache("account-a", "favorites", JsonCodec.encodeTracks(listOf(track)))
            assertNull(store.cache("account-b", "favorites"))
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun pendingFavoritesKeepTheLatestChoiceAcrossRestarts() {
        val name = "pending-favorites-test.db"
        context.deleteDatabase(name)
        try {
            withStore(name) { store ->
                store.queueFavorite("account-a", "42", true)
                store.queueFavorite("account-a", "42", false)
                store.favoriteSynced("account-a", "42", true)
                assertEquals(mapOf("42" to false), store.pendingFavorites("account-a"))
                assertTrue(store.pendingFavorites("account-b").isEmpty())
                store.cache("account-a", "favorites", "[]")
            }
            withStore(name) { store ->
                assertEquals(mapOf("42" to false), store.pendingFavorites("account-a"))
                assertEquals("[]", store.cache("account-a", "favorites"))
                store.favoriteSynced("account-a", "42", false)
                assertTrue(store.pendingFavorites("account-a").isEmpty())
                store.clearCache("account-a")
                assertNull(store.cache("account-a", "favorites"))
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun scanningAFolderKeepsItsTracksAndDropsTheOnesThatDisappeared() {
        val name = "folders-test.db"
        context.deleteDatabase(name)
        try { withStore(name) { store ->
            fun track(id: String) = Track("local:$id", "Track $id", "Artist", localUri = "content://tree/$id")
            listOf("a", "b", "c").forEach { store.save(track(it)) }
            val folder = store.addFolder("content://tree/music", "Music")
            assertEquals(folder, store.addFolder("content://tree/music", "Music"))
            assertEquals(0L, folder.scannedAt)
            store.recordScan(folder.id, setOf("local:a", "local:b"), at = 1_000)
            assertEquals(setOf("local:a", "local:b"), store.folderTrackIds(folder.id))
            assertEquals(2, store.folders().single().trackCount)
            assertEquals(1_000L, store.folders().single().scannedAt)
            // The second scan no longer finds b, and finds c.
            store.recordScan(folder.id, setOf("local:a", "local:c"), at = 2_000)
            assertEquals(setOf("local:a", "local:c"), store.folderTrackIds(folder.id))
            assertNull("A track that left the folder leaves the library", store.get("local:b"))
            assertNotNull(store.get("local:a"))
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun removingAFolderKeepsTracksThatAnotherFolderHolds() {
        val name = "folders-shared-test.db"
        context.deleteDatabase(name)
        try { withStore(name) { store ->
            listOf("shared", "only-first").forEach { store.save(Track("local:$it", it, "Artist", localUri = "content://tree/$it")) }
            val first = store.addFolder("content://tree/first", "First")
            val second = store.addFolder("content://tree/second", "Second")
            store.recordScan(first.id, setOf("local:shared", "local:only-first"))
            store.recordScan(second.id, setOf("local:shared"))
            store.removeFolder(first.id)
            assertEquals(listOf(second.id), store.folders().map { it.id })
            assertNull(store.get("local:only-first"))
            assertNotNull("Another folder still holds this track", store.get("local:shared"))
            store.removeFolder(second.id)
            assertNull(store.get("local:shared"))
            assertTrue(store.folders().isEmpty())
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun upgradesToTheFolderTablesFromVersionThree() {
        val name = "migration-folders.db"
        context.deleteDatabase(name)
        withStore(name) { it.folders() }
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE tracks (id TEXT PRIMARY KEY, payload TEXT NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0)")
            old.execSQL("CREATE TABLE playlists (id TEXT PRIMARY KEY, title TEXT NOT NULL)")
            old.execSQL("CREATE TABLE playlist_tracks (playlist_id TEXT NOT NULL, track_id TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(playlist_id,track_id))")
            old.execSQL("CREATE TABLE cloud_cache (account TEXT NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, updated INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(account,kind))")
            old.execSQL("CREATE TABLE pending_favorites (account TEXT NOT NULL, track_id TEXT NOT NULL, favorite INTEGER NOT NULL, PRIMARY KEY(account,track_id))")
            old.execSQL("CREATE TABLE plays (id INTEGER PRIMARY KEY AUTOINCREMENT, track_id TEXT NOT NULL, played_at INTEGER NOT NULL, listened_ms INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE pending_scrobbles (id INTEGER PRIMARY KEY AUTOINCREMENT, listened_at INTEGER NOT NULL, payload TEXT NOT NULL)")
            old.version = 3
        }
        try { withStore(name) { store ->
            assertTrue(store.folders().isEmpty())
            val folder = store.addFolder("content://tree/upgraded", "Upgraded")
            assertEquals("Upgraded", store.folders().single().name)
            store.removeFolder(folder.id)
        } } finally { context.deleteDatabase(name) }
    }

    private fun file(id: String) = Track("local:$id", "Track $id", "Artist", localUri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2F$id.flac")

    @Test fun anIncompleteScanOnlyAddsAndNeverRemoves() {
        val name = "incomplete-scan-test.db"
        context.deleteDatabase(name)
        try { withStore(name) { store ->
            listOf("a", "b").forEach { store.save(file(it)) }
            val folder = store.addFolder("content://tree/music", "Music")
            store.recordScan(folder.id, setOf("local:a", "local:b"))
            store.save(file("c"))
            // A scan that could not read every folder finds only a and c. The files of the unreadable folder are not gone.
            store.recordScan(folder.id, setOf("local:a", "local:c"), complete = false)
            assertEquals(setOf("local:a", "local:b", "local:c"), store.folderTrackIds(folder.id))
            assertNotNull(store.get("local:b"))
            assertEquals(3, store.folders().single().trackCount)
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun aFileAddedByHandSurvivesTheRemovalOfItsFolder() {
        val name = "direct-file-test.db"
        context.deleteDatabase(name)
        try { withStore(name) { store ->
            listOf("kept", "scanned").forEach { store.save(file(it)) }
            store.recordSource(LocalSource(DocumentKey.of(file("kept").localUri!!), "local:kept", 0, 0, 1, direct = true))
            val folder = store.addFolder("content://tree/music", "Music")
            store.recordScan(folder.id, setOf("local:kept", "local:scanned"))
            assertNull(store.folderOf("local:missing"))
            assertEquals(folder.id, store.folderOf("local:kept")?.id)
            store.removeFolder(folder.id)
            assertNotNull("The listener added this file by hand", store.get("local:kept"))
            assertNull(store.get("local:scanned"))
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun theSameDocumentIsKnownByOneKeyWhateverTheRoute() {
        val name = "sources-test.db"
        context.deleteDatabase(name)
        try { withStore(name) { store ->
            val direct = "content://com.android.externalstorage.documents/document/primary%3AMusic%2Fa.flac"
            val viaTree = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2Fa.flac"
            store.recordSource(LocalSource(DocumentKey.of(direct), "local:a", 5, 10, 1, direct = true))
            assertEquals("local:a", store.sourceFor(DocumentKey.of(viaTree))?.trackId)
            store.save(file("a"))
            store.removeLocal("local:a")
            assertNull("A removed file is read again when it comes back", store.sourceFor(DocumentKey.of(viaTree)))
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun filesFromAnOlderVersionAreMarkedForReading() {
        val name = "migration-sources.db"
        context.deleteDatabase(name)
        val old = file("old")
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE tracks (id TEXT PRIMARY KEY, payload TEXT NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE playlists (id TEXT PRIMARY KEY, title TEXT NOT NULL)")
            db.execSQL("CREATE TABLE playlist_tracks (playlist_id TEXT NOT NULL, track_id TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(playlist_id,track_id))")
            db.execSQL("CREATE TABLE cloud_cache (account TEXT NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, updated INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(account,kind))")
            db.execSQL("CREATE TABLE pending_favorites (account TEXT NOT NULL, track_id TEXT NOT NULL, favorite INTEGER NOT NULL, PRIMARY KEY(account,track_id))")
            db.execSQL("CREATE TABLE plays (id INTEGER PRIMARY KEY AUTOINCREMENT, track_id TEXT NOT NULL, played_at INTEGER NOT NULL, listened_ms INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE pending_scrobbles (id INTEGER PRIMARY KEY AUTOINCREMENT, listened_at INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE local_folders (id TEXT PRIMARY KEY, uri TEXT NOT NULL UNIQUE, name TEXT NOT NULL, scanned_at INTEGER NOT NULL DEFAULT 0, track_count INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE folder_tracks (folder_id TEXT NOT NULL, track_id TEXT NOT NULL, PRIMARY KEY(folder_id, track_id))")
            db.execSQL("INSERT INTO tracks VALUES (?,?,0,0)", arrayOf(old.id, JsonCodec.encodeTrack(old).toString()))
            db.version = 4
        }
        try { withStore(name) { store ->
            val source = store.sourceFor(DocumentKey.of(old.localUri!!))
            assertEquals(old.id, source?.trackId)
            assertEquals("Files added before the upgrade were added by hand", true, source?.direct)
            assertEquals(listOf(old.id), store.outdatedFiles(1).map { it.id })
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun aTrackThatWasAllowedAgainStaysOutOfTheWindowUntilItPlaysAgain() {
        val name = "repeats-test.db"
        context.deleteDatabase(name)
        val one = Track("1", "One", "Artist"); val two = Track("2", "Two", "Artist")
        try { withStore(name) { store ->
            val hour = 3_600_000L; val now = System.currentTimeMillis()
            store.recordPlay(one, 60_000, now - 2 * hour); store.recordPlay(two, 60_000, now - hour); store.recordPlay(one, 60_000, now - 30 * hour)
            assertEquals("The latest play of each track within the window, the latest first", listOf("2" to now - hour, "1" to now - 2 * hour), store.excludedSince(now - 24 * hour))
            assertEquals(setOf("1", "2"), store.playedSince(now - 24 * hour))
            store.allowAgain("1", now - 30 * 60_000L)
            assertEquals("A track that was allowed again after its last play leaves the window", setOf("2"), store.playedSince(now - 24 * hour))
            store.recordPlay(one, 60_000, now)
            assertEquals("A new play starts the window again", listOf("1", "2"), store.excludedSince(now - 24 * hour).map { it.first })
            store.allowAgain("2", now + 1)
            store.clearHistory()
            assertTrue(store.excludedSince(0).isEmpty())
            store.recordPlay(two, 60_000, now + 2)
            assertEquals("Clearing the history also clears what was allowed again", setOf("2"), store.playedSince(0))
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun anOlderDatabaseGetsTheTableOfAllowedTracks() {
        val name = "repeats-upgrade-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE tracks (id TEXT PRIMARY KEY, payload TEXT NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE plays (id INTEGER PRIMARY KEY AUTOINCREMENT, track_id TEXT NOT NULL, played_at INTEGER NOT NULL, listened_ms INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE local_sources (key TEXT PRIMARY KEY, track_id TEXT NOT NULL, modified INTEGER NOT NULL DEFAULT 0, size INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 0, direct INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("INSERT INTO plays (track_id, played_at, listened_ms) VALUES ('1', ?, 60000)", arrayOf(System.currentTimeMillis()))
            db.version = 5
        }
        try { withStore(name) { store ->
            assertEquals(setOf("1"), store.playedSince(0))
            store.allowAgain("1", System.currentTimeMillis() + 1)
            assertTrue(store.playedSince(0).isEmpty())
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun theFilesOfDownloadsAreRememberedUntilTheDownloadLeaves() {
        val name = "download-files-test.db"
        context.deleteDatabase(name)
        try { withStore(name) { store ->
            val track = Track("5", "Five", "Artist", localUri = "content://media/external_primary/audio/media/5")
            store.save(track)
            store.recordDownloadFile("authority|primary:Music/OpenTune/Artist - Five.flac", "5")
            assertTrue(store.isDownloadFile("authority|primary:Music/OpenTune/Artist - Five.flac"))
            assertFalse(store.isDownloadFile("authority|primary:Music/OpenTune/Other.flac"))
            store.removeLocal("5")
            assertFalse("A download that leaves forgets its file", store.isDownloadFile("authority|primary:Music/OpenTune/Artist - Five.flac"))
            assertNull(store.get("5")?.localUri)
        } } finally { context.deleteDatabase(name) }
    }

    @Test fun anOlderDatabaseGetsTheTableOfDownloadFiles() {
        val name = "download-files-upgrade-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE tracks (id TEXT PRIMARY KEY, payload TEXT NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE repeat_releases (track_id TEXT PRIMARY KEY, released_at INTEGER NOT NULL)")
            db.version = 6
        }
        try { withStore(name) { store ->
            store.recordDownloadFile("k", "1")
            assertTrue(store.isDownloadFile("k"))
        } } finally { context.deleteDatabase(name) }
    }
}
