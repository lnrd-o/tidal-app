package io.github.lnrdo.mytidal.data

import io.github.lnrdo.mytidal.R
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import io.github.lnrdo.mytidal.OpenTuneApp
import io.github.lnrdo.mytidal.core.ProtectedStreamException
import io.github.lnrdo.mytidal.core.Track
import io.github.lnrdo.mytidal.core.FlacMp4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.InputStream
import java.io.ByteArrayOutputStream

data class ExportSpec(val fileName: String, val mimeType: String)

/** Unpacks the segments of a download into one audio file, without transcoding. FLAC samples become a native FLAC file with tags. */
@UnstableApi
class OfflineExporter(private val app: OpenTuneApp) {
    private companion object { const val MAX_COVER_BYTES = 5L * 1024 * 1024 }

    /** The name and the type of the file that a download becomes. */
    fun spec(track: Track, stream: io.github.lnrdo.mytidal.core.StreamInfo): ExportSpec {
        val flac = stream.codec.contains("flac", true)
        return ExportSpec(fileName(track, if (flac) "flac" else "m4a"), if (flac) "audio/flac" else "audio/mp4")
    }

    /** The cover of the track, for the tags of a FLAC file. A cover that cannot be fetched leaves the file without one. */
    fun cover(track: Track): FlacMp4.Picture? = runCatching {
        val url = track.artwork?.takeIf { it.startsWith("https://") } ?: return null
        app.http.newBuilder().callTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build().newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful || response.body.contentLength() > MAX_COVER_BYTES) return null
            val type = response.body.contentType()?.let { "${it.type}/${it.subtype}" }?.takeIf { it == "image/jpeg" || it == "image/png" } ?: return null
            val bytes = response.body.bytes()
            if (bytes.size > MAX_COVER_BYTES) null else FlacMp4.Picture(type, bytes)
        }
    }.getOrNull()

    /** "Artist - Title" with the extension. Characters that a file system does not allow become underscores. */
    fun fileName(track: Track, extension: String): String {
        val name = "${track.artist} - ${track.title}".replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|]"), "_").trim().take(160)
        return "${name.ifBlank { app.getString(R.string.export_default_name) }}.$extension"
    }

    /** Writes the audio of the download of [track] to [output], with the tags of the track and [cover] when the audio is FLAC. */
    suspend fun writeDash(track: Track, output: OutputStream, cover: FlacMp4.Picture? = null, onProgress: (Int) -> Unit = {}) {
        val stream = app.sources.readOffline(track.id)
        val xml = stream.dashManifest ?: throw IOException("This download has no DASH manifest.")
        val manifest = DashManifestParser().parse(Uri.parse(app.sources.manifestUri(stream)), ByteArrayInputStream(xml.toByteArray()))
        if (manifest.dynamic || manifest.periodCount != 1) throw IOException("This download cannot be exported as one audio file.")
        val representation = manifest.getPeriod(0).adaptationSets.filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { it.representations }.singleOrNull() ?: throw IOException("This download contains multiple audio variants.")
        if (representation.format.drmInitData != null) throw ProtectedStreamException()
        val index = representation.index ?: throw IOException("The download has no segment index.")
        val count = index.getSegmentCount(manifest.getPeriodDurationUs(0))
        if (count !in 1..100_000) throw IOException("The download has an unsupported segment index.")
        val base = representation.baseUrls.first().url
        val initialization = representation.initializationUri ?: throw IOException("The download has no audio initialization segment.")
        val ranges = sequence {
            yield(initialization)
            for (number in index.firstSegmentNum until index.firstSegmentNum + count) yield(index.getSegmentUrl(number))
        }
        val flac = stream.codec.contains("flac", true)
        ranges.forEachIndexed { position, range ->
            currentCoroutineContext().ensureActive()
            val spec = DashUtil.buildDataSpec(representation, base, range, 0, emptyMap())
            DataSourceInputStream(app.sources.dataSource(offlineOnly = true).createDataSource(), spec).use { input ->
                if (flac && position == 0) {
                    val initialization = initializationBytes(input)
                    output.write(FlacMp4.flacHeader(initialization, tags(track), cover))
                } else if (flac) FlacMp4.copyFrames(input, output)
                else input.copyTo(output, 64 * 1024)
            }
            onProgress(((position + 1) * 100L / (count + 1)).toInt())
        }
    }

    private fun initializationBytes(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > 2 * 1024 * 1024) throw IOException("The audio initialization is too large.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun tags(track: Track): Map<String, String> = linkedMapOf("TITLE" to track.title, "ARTIST" to track.artist, "ALBUM" to track.album).apply {
        if (track.trackNumber > 0) put("TRACKNUMBER", track.trackNumber.toString())
    }.filterValues(String::isNotBlank)
}
