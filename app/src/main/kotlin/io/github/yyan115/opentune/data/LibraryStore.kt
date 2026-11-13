package io.github.yyan115.opentune.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.yyan115.opentune.core.JsonCodec
import io.github.yyan115.opentune.core.LocalPlaylist
import io.github.yyan115.opentune.core.DocumentKey
import io.github.yyan115.opentune.core.Track
import io.github.yyan115.opentune.core.isDeviceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class LibraryStore(context: Context, databaseName: String = "library.db") : SQLiteOpenHelper(context, databaseName, null, 8) {
    private val revision = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tracks (id TEXT PRIMARY KEY, payload TEXT NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE playlists (id TEXT PRIMARY KEY, title TEXT NOT NULL)")
        db.execSQL("CREATE TABLE playlist_tracks (playlist_id TEXT NOT NULL, track_id TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(playlist_id,track_id), FOREIGN KEY(playlist_id) REFERENCES playlists(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX recent_tracks ON tracks(played DESC)")
        createCollectionTables(db)
        createHistoryTables(db)
        createFolderTables(db)
        createSourceTables(db)
        createRepeatTables(db)
        createDownloadFileTables(db)
        createCueTables(db)
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true); db.enableWriteAheadLogging() }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createCollectionTables(db)
        if (oldVersion < 3) {
            // Version 1 databases just received the new cache table above, which already has the column.
            if (oldVersion == 2) db.execSQL("ALTER TABLE cloud_cache ADD COLUMN updated INTEGER NOT NULL DEFAULT 0")
            createHistoryTables(db)
        }
        if (oldVersion < 4) createFolderTables(db)
        if (oldVersion < 5) {
            createSourceTables(db)
            // Files that exist already were found by a scan or added by hand. The next scan reads them again, because their version is zero.
            db.rawQuery("SELECT id, payload FROM tracks WHERE id LIKE 'local:%'", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val track = runCatching { JsonCodec.decodeTrack(JSONObject(cursor.getString(1))) }.getOrNull() ?: continue
                    val uri = track.localUri ?: continue
                    val inFolder = db.rawQuery("SELECT 1 FROM folder_tracks WHERE track_id=?", arrayOf(track.id)).use { it.moveToFirst() }
                    db.insertWithOnConflict("local_sources", null, ContentValues().apply {
                        put("key", DocumentKey.of(uri)); put("track_id", track.id); put("direct", if (inFolder) 0 else 1)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
        }
        if (oldVersion < 6) createRepeatTables(db)
        if (oldVersion < 7) createDownloadFileTables(db)
        if (oldVersion < 8) createCueTables(db)
    }

    /** A file on the device is known by the document it came from. [version] says how it was read, [direct] says that the listener added it by hand. */
    /** The audio files that downloads became, by the key of the file. A folder scan leaves these files out, because their tracks are known. */
    /** The cue points that D'JAI found in a song: its drop, breaks and outro, in milliseconds from the start. */
    private fun createCueTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cue_points (track_id TEXT NOT NULL, at_ms INTEGER NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(track_id, at_ms))")
    }

    @Synchronized fun cues(trackId: String): List<Pair<String, Long>> = readableDatabase.rawQuery(
        "SELECT kind, at_ms FROM cue_points WHERE track_id=? ORDER BY at_ms", arrayOf(trackId)).use { cursor ->
        val list = ArrayList<Pair<String, Long>>()
        while (cursor.moveToNext()) list.add(cursor.getString(0) to cursor.getLong(1))
        list
    }

    /** Replaces the cue points of a song with [cues], as kind and milliseconds. */
    @Synchronized fun saveCues(trackId: String, cues: List<Pair<String, Long>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("cue_points", "track_id=?", arrayOf(trackId))
            for ((kind, at) in cues) db.insertWithOnConflict("cue_points", null, ContentValues().apply { put("track_id", trackId); put("at_ms", at); put("kind", kind) }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun createDownloadFileTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE download_files (key TEXT PRIMARY KEY, track_id TEXT NOT NULL)")
    }

    @Synchronized fun recordDownloadFile(key: String, trackId: String) {
        writableDatabase.insertWithOnConflict("download_files", null, ContentValues().apply { put("key", key); put("track_id", trackId) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun isDownloadFile(key: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM download_files WHERE key=?", arrayOf(key)).use { it.moveToFirst() }

    @Synchronized fun removeDownloadFile(trackId: String) { writableDatabase.delete("download_files", "track_id=?", arrayOf(trackId)) }

    /** A track that the listener allowed again stays out of the avoid-repeats window until it plays once more. */
    private fun createRepeatTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE repeat_releases (track_id TEXT PRIMARY KEY, released_at INTEGER NOT NULL)")
    }

    private fun createSourceTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE local_sources (key TEXT PRIMARY KEY, track_id TEXT NOT NULL, modified INTEGER NOT NULL DEFAULT 0, size INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 0, direct INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX local_sources_track ON local_sources(track_id)")
    }

    private fun createFolderTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE local_folders (id TEXT PRIMARY KEY, uri TEXT NOT NULL UNIQUE, name TEXT NOT NULL, scanned_at INTEGER NOT NULL DEFAULT 0, track_count INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE folder_tracks (folder_id TEXT NOT NULL, track_id TEXT NOT NULL, PRIMARY KEY(folder_id, track_id), FOREIGN KEY(folder_id) REFERENCES local_folders(id) ON DELETE CASCADE)")
    }

    private fun createHistoryTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE plays (id INTEGER PRIMARY KEY AUTOINCREMENT, track_id TEXT NOT NULL, played_at INTEGER NOT NULL, listened_ms INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX plays_time ON plays(played_at DESC)")
        db.execSQL("CREATE TABLE pending_scrobbles (id INTEGER PRIMARY KEY AUTOINCREMENT, listened_at INTEGER NOT NULL, payload TEXT NOT NULL)")
    }

    private fun createCollectionTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cloud_cache (account TEXT NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL, updated INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(account,kind))")
        db.execSQL("CREATE TABLE pending_favorites (account TEXT NOT NULL, track_id TEXT NOT NULL, favorite INTEGER NOT NULL, PRIMARY KEY(account,track_id))")
    }

    @Synchronized fun cache(account: String, kind: String): String? = readableDatabase.rawQuery(
        "SELECT payload FROM cloud_cache WHERE account=? AND kind=?", arrayOf(account, kind)).use { if (it.moveToFirst()) it.getString(0) else null }

    @Synchronized fun cache(account: String, kind: String, payload: String) {
        if (account.isBlank()) return
        writableDatabase.insertWithOnConflict("cloud_cache", null, ContentValues().apply {
            put("account", account); put("kind", kind); put("payload", payload); put("updated", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Keeps only the [keep] most recently written cache entries whose kind starts with [prefix]. */
    @Synchronized fun pruneCache(prefix: String, keep: Int) {
        writableDatabase.execSQL("DELETE FROM cloud_cache WHERE kind LIKE ? AND rowid NOT IN (SELECT rowid FROM cloud_cache WHERE kind LIKE ? ORDER BY updated DESC LIMIT ?)",
            arrayOf<Any>("$prefix%", "$prefix%", keep))
    }

    @Synchronized fun cacheAge(account: String, kind: String): Long = readableDatabase.rawQuery(
        "SELECT updated FROM cloud_cache WHERE account=? AND kind=?", arrayOf(account, kind)).use { if (it.moveToFirst()) System.currentTimeMillis() - it.getLong(0) else Long.MAX_VALUE }

    @Synchronized fun clearCache(account: String) { writableDatabase.delete("cloud_cache", "account=?", arrayOf(account)) }

    @Synchronized fun queueFavorite(account: String, trackId: String, favorite: Boolean) {
        if (account.isBlank()) return
        writableDatabase.insertWithOnConflict("pending_favorites", null, ContentValues().apply {
            put("account", account); put("track_id", trackId); put("favorite", if (favorite) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized fun pendingFavorites(account: String): Map<String, Boolean> = readableDatabase.rawQuery(
        "SELECT track_id,favorite FROM pending_favorites WHERE account=?", arrayOf(account)).use { cursor ->
        buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1) == 1) }
    }

    @Synchronized fun favoriteSynced(account: String, trackId: String, favorite: Boolean) {
        writableDatabase.delete("pending_favorites", "account=? AND track_id=? AND favorite=?", arrayOf(account, trackId, if (favorite) "1" else "0"))
    }

    @Synchronized fun save(track: Track) {
        val previous = get(track.id)
        val merged = if (previous?.localUri != null && track.localUri == null) track.copy(localUri = previous.localUri, quality = previous.quality) else track
        val values = ContentValues().apply { put("id", track.id); put("payload", JsonCodec.encodeTrack(merged).toString()) }
        writableDatabase.insertWithOnConflict("tracks", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        writableDatabase.update("tracks", values, "id=?", arrayOf(track.id))
        revision.value++
    }

    /** Stores many tracks in one step, keeping the copies that are already stored. Used for browse lists. */
    @Synchronized fun saveAll(tracks: List<Track>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            tracks.forEach { track ->
                db.insertWithOnConflict("tracks", null, ContentValues().apply { put("id", track.id); put("payload", JsonCodec.encodeTrack(track).toString()) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun get(id: String): Track? = readableDatabase.rawQuery("SELECT payload FROM tracks WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) JsonCodec.decodeTrack(JSONObject(it.getString(0))) else null
    }

    @Synchronized fun localTracks(): List<Track> = queryTracks("SELECT payload FROM tracks WHERE payload LIKE '%\"localUri\"%' ORDER BY rowid DESC").filter(Track::onDevice).sortedByDescending(Track::addedAt)
    @Synchronized fun favorites(): List<Track> = queryTracks("SELECT payload FROM tracks WHERE favorite=1 ORDER BY rowid DESC")
    @Synchronized fun recent(): List<Track> = queryTracks("SELECT payload FROM tracks WHERE played>0 ORDER BY played DESC LIMIT 30")
    @Synchronized fun isFavorite(id: String): Boolean = readableDatabase.rawQuery("SELECT favorite FROM tracks WHERE id=?", arrayOf(id)).use { it.moveToFirst() && it.getInt(0) == 1 }

    @Synchronized fun setFavorite(track: Track, favorite: Boolean) {
        save(track)
        writableDatabase.update("tracks", ContentValues().apply { put("favorite", if (favorite) 1 else 0) }, "id=?", arrayOf(track.id))
        revision.value++
    }

    @Synchronized fun played(track: Track) {
        save(track)
        writableDatabase.update("tracks", ContentValues().apply { put("played", System.currentTimeMillis()) }, "id=?", arrayOf(track.id))
        revision.value++
    }

    /** Records that a track was listened to for [listenedMs]. These rows feed the local listening statistics. */
    @Synchronized fun recordPlay(track: Track, listenedMs: Long, at: Long = System.currentTimeMillis()) {
        save(track)
        writableDatabase.insert("plays", null, ContentValues().apply { put("track_id", track.id); put("played_at", at); put("listened_ms", listenedMs) })
    }

    data class PlayCount(val track: Track, val plays: Int, val listenedMs: Long)

    @Synchronized fun topTracks(since: Long, limit: Int = 25): List<PlayCount> = readableDatabase.rawQuery(
        "SELECT t.payload, COUNT(*) c, SUM(p.listened_ms) FROM plays p JOIN tracks t ON t.id=p.track_id WHERE p.played_at>=? GROUP BY p.track_id ORDER BY c DESC, SUM(p.listened_ms) DESC LIMIT ?",
        arrayOf(since.toString(), limit.toString())).use {
        buildList { while (it.moveToNext()) add(PlayCount(JsonCodec.decodeTrack(JSONObject(it.getString(0))), it.getInt(1), it.getLong(2))) }
    }

    @Synchronized fun listeningTotals(since: Long): Pair<Int, Long> = readableDatabase.rawQuery(
        "SELECT COUNT(*), COALESCE(SUM(listened_ms),0) FROM plays WHERE played_at>=?", arrayOf(since.toString())).use { it.moveToFirst(); it.getInt(0) to it.getLong(1) }

    data class PlayEntry(val track: Track, val playedAt: Long, val listenedMs: Long)

    @Synchronized fun exportPlays(limit: Int = 5000): List<PlayEntry> = readableDatabase.rawQuery(
        "SELECT t.payload, p.played_at, p.listened_ms FROM plays p JOIN tracks t ON t.id=p.track_id ORDER BY p.played_at DESC LIMIT ?", arrayOf(limit.toString())).use {
        buildList { while (it.moveToNext()) add(PlayEntry(JsonCodec.decodeTrack(JSONObject(it.getString(0))), it.getLong(1), it.getLong(2))) }
    }

    /** Adds history entries that are not stored yet and returns how many were new. */
    @Synchronized fun importPlays(entries: List<PlayEntry>): Int {
        var added = 0
        for (entry in entries) {
            val known = readableDatabase.rawQuery("SELECT 1 FROM plays WHERE track_id=? AND played_at=?", arrayOf(entry.track.id, entry.playedAt.toString())).use { it.moveToFirst() }
            if (!known) { recordPlay(entry.track, entry.listenedMs, entry.playedAt); added++ }
        }
        return added
    }

    @Synchronized fun moveInPlaylist(playlistId: String, from: Int, to: Int) {
        val ids = readableDatabase.rawQuery("SELECT track_id FROM playlist_tracks WHERE playlist_id=? ORDER BY position", arrayOf(playlistId)).use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }.toMutableList()
        if (from !in ids.indices || to !in ids.indices || from == to) return
        ids.add(to, ids.removeAt(from))
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEachIndexed { position, id -> db.update("playlist_tracks", ContentValues().apply { put("position", position) }, "playlist_id=? AND track_id=?", arrayOf(playlistId, id)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        revision.value++
    }

    /**
     * The ids of the tracks that played since [sinceMs], in epoch milliseconds, without the tracks that the listener allowed again
     * after their last play. These are the tracks that the avoid-repeats window excludes.
     */
    @Synchronized fun playedSince(sinceMs: Long): Set<String> = excludedSince(sinceMs).map { it.first }.toSet()

    /** The tracks that the avoid-repeats window excludes, each with the time of its last play, the latest play first. */
    @Synchronized fun excludedSince(sinceMs: Long): List<Pair<String, Long>> = readableDatabase.rawQuery(
        "SELECT p.track_id, MAX(p.played_at) FROM plays p LEFT JOIN repeat_releases r ON r.track_id=p.track_id " +
            "WHERE p.played_at>=? AND (r.released_at IS NULL OR p.played_at>r.released_at) GROUP BY p.track_id ORDER BY MAX(p.played_at) DESC",
        arrayOf(sinceMs.toString())).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getLong(1)) } }

    /** Lets a track play again at once. Its next play starts the window anew. */
    @Synchronized fun allowAgain(trackId: String, at: Long = System.currentTimeMillis()) {
        writableDatabase.insertWithOnConflict("repeat_releases", null, ContentValues().apply { put("track_id", trackId); put("released_at", at) }, SQLiteDatabase.CONFLICT_REPLACE)
        revision.value++
    }

    @Synchronized fun clearHistory() {
        writableDatabase.delete("plays", null, null); writableDatabase.delete("repeat_releases", null, null)
        writableDatabase.update("tracks", ContentValues().apply { put("played", 0) }, null, null); revision.value++
    }

    @Synchronized fun queueScrobble(listenedAt: Long, payload: String) {
        writableDatabase.insert("pending_scrobbles", null, ContentValues().apply { put("listened_at", listenedAt); put("payload", payload) })
    }
    @Synchronized fun pendingScrobbles(limit: Int = 50): List<Pair<Long, String>> = readableDatabase.rawQuery(
        "SELECT id,payload FROM pending_scrobbles ORDER BY id LIMIT ?", arrayOf(limit.toString())).use { buildList { while (it.moveToNext()) add(it.getLong(0) to it.getString(1)) } }
    @Synchronized fun scrobblesSent(ids: List<Long>) { ids.forEach { writableDatabase.delete("pending_scrobbles", "id=?", arrayOf(it.toString())) } }

    @Synchronized fun removeLocal(id: String) {
        if (isDeviceId(id)) {
            writableDatabase.beginTransaction()
            try {
                writableDatabase.delete("playlist_tracks", "track_id=?", arrayOf(id))
                writableDatabase.delete("local_sources", "track_id=?", arrayOf(id))
                writableDatabase.delete("folder_tracks", "track_id=?", arrayOf(id))
                writableDatabase.delete("tracks", "id=?", arrayOf(id))
                writableDatabase.setTransactionSuccessful()
            } finally { writableDatabase.endTransaction() }
        } else {
            get(id)?.let { track -> writableDatabase.update("tracks", ContentValues().apply {
                put("payload", JsonCodec.encodeTrack(track.copy(localUri = null, quality = null)).toString())
            }, "id=?", arrayOf(id)) }
            writableDatabase.delete("download_files", "track_id=?", arrayOf(id))
        }
        revision.value++
    }

    @Synchronized fun createPlaylist(title: String): LocalPlaylist {
        val trimmed = title.trim()
        require(trimmed.isNotEmpty())
        val id = "local:${UUID.randomUUID()}"
        writableDatabase.insertOrThrow("playlists", null, ContentValues().apply { put("id", id); put("title", trimmed) })
        revision.value++
        return LocalPlaylist(id, trimmed)
    }

    @Synchronized fun playlists(): List<LocalPlaylist> = readableDatabase.rawQuery("SELECT id,title FROM playlists ORDER BY title COLLATE NOCASE", null).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val ids = readableDatabase.rawQuery("SELECT track_id FROM playlist_tracks WHERE playlist_id=? ORDER BY position", arrayOf(id)).use {
                    buildList { while (it.moveToNext()) add(it.getString(0)) }
                }
                val cover = ids.asSequence().take(12).mapNotNull { get(it)?.artwork }.firstOrNull()
                add(LocalPlaylist(id, cursor.getString(1), ids, cover))
            }
        }
    }

    @Synchronized fun addToPlaylist(playlistId: String, track: Track) {
        save(track)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val position = db.rawQuery("SELECT COALESCE(MAX(position),-1)+1 FROM playlist_tracks WHERE playlist_id=?", arrayOf(playlistId)).use { it.moveToFirst(); it.getInt(0) }
            db.insertWithOnConflict("playlist_tracks", null, ContentValues().apply {
                put("playlist_id", playlistId); put("track_id", track.id); put("position", position)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        revision.value++
    }

    @Synchronized fun playlistTracks(id: String): List<Track> = readableDatabase.rawQuery(
        "SELECT t.payload FROM tracks t JOIN playlist_tracks p ON t.id=p.track_id WHERE p.playlist_id=? ORDER BY p.position", arrayOf(id)).use {
        buildList { while (it.moveToNext()) add(JsonCodec.decodeTrack(JSONObject(it.getString(0)))) }
    }

    @Synchronized fun deletePlaylist(id: String) {
        writableDatabase.delete("playlists", "id=?", arrayOf(id)); revision.value++
    }

    @Synchronized fun renamePlaylist(id: String, title: String) {
        require(title.isNotBlank())
        writableDatabase.update("playlists", ContentValues().apply { put("title", title.trim()) }, "id=?", arrayOf(id))
        revision.value++
    }

    @Synchronized fun removeFromPlaylist(id: String, trackId: String) {
        writableDatabase.delete("playlist_tracks", "playlist_id=? AND track_id=?", arrayOf(id, trackId))
        revision.value++
    }

    @Synchronized private fun queryTracks(sql: String): List<Track> = readableDatabase.rawQuery(sql, null).use {
        buildList { while (it.moveToNext()) add(JsonCodec.decodeTrack(JSONObject(it.getString(0)))) }
    }

    // Folders on the device that the listener chose, and the tracks that a scan found in each.

    @Synchronized fun folders(): List<LocalFolder> = readableDatabase.rawQuery(
        "SELECT id, uri, name, scanned_at, track_count FROM local_folders ORDER BY name COLLATE NOCASE", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(LocalFolder(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getInt(4))) }
    }

    /** Adds a folder, or returns the one that exists for the same address. */
    @Synchronized fun addFolder(uri: String, name: String): LocalFolder {
        val id = java.security.MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        writableDatabase.insertWithOnConflict("local_folders", null, ContentValues().apply { put("id", id); put("uri", uri); put("name", name) }, SQLiteDatabase.CONFLICT_IGNORE)
        revision.value++
        return folders().first { it.id == id }
    }

    /** Removes a folder and the files that no other folder holds and that the listener did not add by hand. */
    @Synchronized fun removeFolder(id: String) {
        val orphans = folderTrackIds(id) - otherFolderTrackIds(id) - directTrackIds()
        orphans.forEach(::removeLocal)
        writableDatabase.delete("local_folders", "id=?", arrayOf(id))
        revision.value++
    }

    @Synchronized fun folderTrackIds(id: String): Set<String> = readableDatabase.rawQuery("SELECT track_id FROM folder_tracks WHERE folder_id=?", arrayOf(id)).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun otherFolderTrackIds(id: String): Set<String> = readableDatabase.rawQuery("SELECT track_id FROM folder_tracks WHERE folder_id<>?", arrayOf(id)).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    /** The ids of every file that a folder holds. */
    @Synchronized fun folderFileIds(): Set<String> = readableDatabase.rawQuery("SELECT DISTINCT track_id FROM folder_tracks", null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    /** The folder that holds a file, or null for a file that the listener added by hand. */
    @Synchronized fun folderOf(trackId: String): LocalFolder? = readableDatabase.rawQuery(
        "SELECT f.id, f.uri, f.name, f.scanned_at, f.track_count FROM local_folders f JOIN folder_tracks t ON t.folder_id=f.id WHERE t.track_id=? LIMIT 1", arrayOf(trackId)).use {
        if (it.moveToFirst()) LocalFolder(it.getString(0), it.getString(1), it.getString(2), it.getLong(3), it.getInt(4)) else null
    }

    /**
     * Stores the result of a scan. With [complete] set, files that the folder held before and no longer holds are removed from the library.
     * An incomplete scan, one that could not read every folder, only adds files.
     */
    @Synchronized fun recordScan(id: String, found: Set<String>, complete: Boolean = true, at: Long = System.currentTimeMillis()) {
        val previous = folderTrackIds(id)
        if (complete) {
            val keep = found + otherFolderTrackIds(id) + directTrackIds()
            (previous - keep).forEach(::removeLocal)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (complete) db.delete("folder_tracks", "folder_id=?", arrayOf(id))
            found.forEach { db.insertWithOnConflict("folder_tracks", null, ContentValues().apply { put("folder_id", id); put("track_id", it) }, SQLiteDatabase.CONFLICT_IGNORE) }
            val count = if (complete) found.size else folderTrackIds(id).size
            db.update("local_folders", ContentValues().apply { put("scanned_at", at); put("track_count", count) }, "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        revision.value++
    }

    // The documents that files came from. A file that was found twice, by hand and by a scan, is still one track.

    @Synchronized fun sourceFor(key: String): LocalSource? = readableDatabase.rawQuery(
        "SELECT track_id, modified, size, version, direct FROM local_sources WHERE key=?", arrayOf(key)).use {
        if (it.moveToFirst()) LocalSource(key, it.getString(0), it.getLong(1), it.getLong(2), it.getInt(3), it.getInt(4) == 1) else null
    }

    @Synchronized fun recordSource(source: LocalSource) {
        writableDatabase.insertWithOnConflict("local_sources", null, ContentValues().apply {
            put("key", source.key); put("track_id", source.trackId); put("modified", source.modified); put("size", source.size)
            put("version", source.version); put("direct", if (source.direct) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun directTrackIds(): Set<String> = readableDatabase.rawQuery("SELECT track_id FROM local_sources WHERE direct=1", null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    /** The files that were read by an older version of the importer, or not read at all. */
    @Synchronized fun outdatedFiles(version: Int): List<Track> = readableDatabase.rawQuery(
        "SELECT DISTINCT s.track_id FROM local_sources s JOIN tracks t ON t.id=s.track_id WHERE s.version<?", arrayOf(version.toString())).use { cursor ->
        buildList { while (cursor.moveToNext()) get(cursor.getString(0))?.let(::add) }
    }

    /** Whether a file was added by hand. */
    @Synchronized fun isDirect(trackId: String): Boolean = trackId in directTrackIds()
}

/** How a file entered the library. [modified] and [size] come from the document provider and tell whether the file changed. */
data class LocalSource(val key: String, val trackId: String, val modified: Long, val size: Long, val version: Int, val direct: Boolean)

/** A folder on the device that holds music. [scannedAt] is zero until the first scan finishes. */
data class LocalFolder(val id: String, val uri: String, val name: String, val scannedAt: Long, val trackCount: Int)
