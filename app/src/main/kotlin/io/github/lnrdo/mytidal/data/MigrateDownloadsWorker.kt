package io.github.lnrdo.mytidal.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.media3.common.util.UnstableApi
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.lnrdo.mytidal.OpenTuneApp
import io.github.lnrdo.mytidal.R
import io.github.lnrdo.mytidal.core.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Moves downloads that an earlier version kept as segments in the private storage of the app into the download folder, one tagged file for each track.
 * The segments stay until the file is written and can be read, so a track that fails to move keeps playing as before.
 */
@UnstableApi
class DownloadMigration(private val app: OpenTuneApp) {
    /** The downloads that are still kept as segments. */
    fun pending(): List<Track> = app.library.localTracks().filter { it.localUri?.startsWith("opentune-offline:") == true }

    /** Moves one download. A failure leaves the download, its segments and the library as they were. */
    suspend fun move(track: Track) {
        val stream = app.sources.readOffline(track.id)
        val spec = app.exporter.spec(track, stream)
        val cover = if (spec.mimeType == "audio/flac") app.exporter.cover(track) else null
        val stored = app.downloadFiles.create(spec.fileName, spec.mimeType) { output -> app.exporter.writeDash(track, output, cover) }
        if (!app.downloadFiles.readable(stored.uri)) {
            app.downloadFiles.delete(stored.uri)
            throw IOException("The audio file could not be read after it was written.")
        }
        app.library.save((app.library.get(track.id) ?: track).copy(localUri = stored.uri.toString()))
        app.library.recordDownloadFile(stored.key, track.id)
        runCatching { app.sources.removeOffline(track.id) }
    }

    /** Moves every pending download and returns how many moved. [onProgress] gets the number of the download in work and the total. */
    suspend fun moveAll(onProgress: suspend (Int, Int) -> Unit = { _, _ -> }): Int {
        val pending = pending()
        var moved = 0
        pending.forEachIndexed { index, track ->
            onProgress(index + 1, pending.size)
            try { move(track); moved++ } catch (error: Exception) { if (error is CancellationException) throw error }
        }
        return moved
    }
}

/** Runs [DownloadMigration] in the background with a notification that shows its progress. */
@UnstableApi
class MigrateDownloadsWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val app get() = applicationContext as OpenTuneApp

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!app.downloadFiles.canWrite()) return@withContext Result.success()
        DownloadMigration(app).moveAll { position, total -> setForeground(notification(position, total)) }
        Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = notification(0, 0)

    private fun notification(position: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("downloads", applicationContext.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(applicationContext, "downloads").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.migrate_title)).setContentText(if (total > 0) applicationContext.getString(R.string.migrate_text, position, total) else null)
            .setProgress(total, position, total == 0).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS).build()
        return if (android.os.Build.VERSION.SDK_INT >= 29) ForegroundInfo(2090, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(2090, notification)
    }
}
