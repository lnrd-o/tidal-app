package io.github.yyan115.opentune.playback

import io.github.yyan115.opentune.core.ServiceException
import io.github.yyan115.opentune.core.Problem
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.github.yyan115.opentune.OpenTuneApp
import io.github.yyan115.opentune.core.StreamInfo
import io.github.yyan115.opentune.core.Track
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
class AudioSources(private val app: OpenTuneApp) {
    private val qualities = ConcurrentHashMap<String, String>()
    private val gains = ConcurrentHashMap<String, Gains>()
    fun quality(id: String): String? = qualities[id]
    fun gains(id: String): Gains? = gains[id]
    /** Called on any thread when the gain of a track becomes known. */
    @Volatile var onGains: ((String) -> Unit)? = null

    /** ReplayGain values in decibels and linear peak amplitudes, as returned by TIDAL for a track and its album. */
    data class Gains(val track: Double?, val trackPeak: Double?, val album: Double?, val albumPeak: Double?)
    val cache by lazy { SimpleCache(File(app.filesDir, "offline_audio"), NoOpCacheEvictor(), StandaloneDatabaseProvider(app)) }
    private val evictor by lazy { AdjustableEvictor(app.settings.state.value.cacheMb * MEGABYTE) }
    private val streamingCache by lazy { SimpleCache(File(app.cacheDir, "streaming_audio"), evictor, StandaloneDatabaseProvider(app)) }
    /** Sets the size of the cache of listened songs, in megabytes, and removes the oldest songs at once if it is now too big. */
    fun setCacheLimit(megabytes: Int) {
        val limit = megabytes * MEGABYTE
        if (evictor.maxBytes == limit) return
        evictor.maxBytes = limit; evictor.trim(streamingCache)
    }
    /** The bytes that the cache of listened songs holds now. */
    fun cacheBytes(): Long = streamingCache.cacheSpace
    /** A silent connection fails after twelve seconds, so the player retries before its buffer runs dry. */
    private val streamingHttp by lazy { app.http.newBuilder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).readTimeout(12, java.util.concurrent.TimeUnit.SECONDS).build() }
    private val upstream: DataSource.Factory by lazy { DefaultDataSource.Factory(app, OkHttpDataSource.Factory(streamingHttp)) }
    fun dataSource(offlineOnly: Boolean = false): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache).setCacheWriteDataSinkFactory(null)
        .setUpstreamDataSourceFactory(if (offlineOnly) DefaultDataSource.Factory(app, androidx.media3.datasource.PlaceholderDataSource.FACTORY)
            else CacheDataSource.Factory().setCache(streamingCache).setUpstreamDataSourceFactory(upstream))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun downloadSource(): CacheDataSource.Factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
    fun clearStreamingCache() { streamingCache.keys.toList().forEach(streamingCache::removeResource) }

    fun wifi(): Boolean {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    suspend fun resolve(track: Track): MediaItem {
        val saved = app.library.get(track.id) ?: track
        saved.localUri?.let { local ->
            if (local.startsWith("opentune-offline:")) return item(saved, readOffline(saved.id))
            // The file of a download is in a folder that the listener can reach. When it is gone, the track is a stream again.
            if (!saved.isDownload || app.downloadFiles.readable(Uri.parse(local))) return metadata(saved).setUri(local).build()
            app.library.removeLocal(saved.id)
        }
        if (app.settings.state.value.offline) throw ServiceException(0, "This track is not available offline. Download it first.", Problem.NOT_AVAILABLE_OFFLINE)
        val settings = app.settings.state.value
        val stream = app.tidal.stream(track.id, if (wifi()) settings.quality else settings.mobileQuality)
        return item(track, stream)
    }

    fun item(track: Track, stream: StreamInfo): MediaItem {
        qualities[track.id] = stream.qualityLabel
        gains[track.id] = Gains(stream.trackGain, stream.trackPeak, stream.albumGain, stream.albumPeak)
        onGains?.invoke(track.id)
        return metadata(track).setUri(stream.uri ?: manifestUri(stream))
            .setMimeType(stream.mimeType).setTag(stream.qualityLabel).build()
    }

    fun removeOffline(id: String) {
        val stream = readOffline(id)
        val item = MediaItem.Builder().setUri(manifestUri(stream)).setMimeType(MimeTypes.APPLICATION_MPD).build()
        androidx.media3.exoplayer.dash.offline.DashDownloader.Factory(downloadSource()).setExecutor(java.util.concurrent.Executor { it.run() }).create(item).remove()
        check(offlineFile(id).delete()) { "The download record could not be removed." }
        qualities.remove(id)
    }

    fun unresolved(track: Track): MediaItem = metadata(track).setUri("opentune://track/${Uri.encode(track.id)}").build()

    private fun metadata(track: Track): MediaItem.Builder = MediaItem.Builder().setMediaId(track.id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
            .setArtworkUri(track.artwork?.let(Uri::parse)).setIsBrowsable(false).setIsPlayable(true)
            .setExtras(android.os.Bundle().apply { putString("track", io.github.yyan115.opentune.core.JsonCodec.encodeTrack(track).toString()) }).build())

    fun manifestUri(stream: StreamInfo): String = "data:application/dash+xml;base64," + Base64.getEncoder().encodeToString(
        requireNotNull(stream.dashManifest).toByteArray(Charsets.UTF_8))

    fun offlineFile(id: String): File = File(app.filesDir, "downloads").apply { mkdirs() }.resolve(safeId(id) + ".json")

    fun saveOffline(id: String, stream: StreamInfo) {
        val file = offlineFile(id)
        val temp = File(file.path + ".tmp")
        temp.writeText(JSONObject().apply {
            put("uri", stream.uri); put("manifest", stream.dashManifest); put("mime", stream.mimeType)
            put("codec", stream.codec); put("quality", stream.quality); put("bitDepth", stream.bitDepth); put("sampleRate", stream.sampleRate)
            stream.trackGain?.let { put("trackGain", it) }; stream.trackPeak?.let { put("trackPeak", it) }
            stream.albumGain?.let { put("albumGain", it) }; stream.albumPeak?.let { put("albumPeak", it) }
        }.toString())
        check(temp.renameTo(file)) { "The download record could not be saved." }
    }

    fun readOffline(id: String): StreamInfo {
        val json = JSONObject(offlineFile(id).readText())
        return StreamInfo(json.optString("uri").takeIf { it.isNotBlank() && it != "null" },
            json.optString("manifest").takeIf { it.isNotBlank() && it != "null" }, json.getString("mime"),
            json.getString("codec"), json.getString("quality"), json.optInt("bitDepth"), json.optInt("sampleRate"),
            json.optDouble("trackGain", Double.NaN).takeIf { !it.isNaN() }, json.optDouble("trackPeak", Double.NaN).takeIf { !it.isNaN() },
            json.optDouble("albumGain", Double.NaN).takeIf { !it.isNaN() }, json.optDouble("albumPeak", Double.NaN).takeIf { !it.isNaN() })
    }

    companion object {
        private const val MEGABYTE = 1024L * 1024L
        fun safeId(id: String): String = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
    }
}
