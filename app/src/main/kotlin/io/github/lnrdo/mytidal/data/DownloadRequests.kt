package io.github.lnrdo.mytidal.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.media3.common.util.UnstableApi
import io.github.lnrdo.mytidal.OpenTuneApp
import io.github.lnrdo.mytidal.core.JsonCodec
import io.github.lnrdo.mytidal.core.Track

/** Queues downloads. The player service and the screens share these rules. */
@UnstableApi
object DownloadRequests {
    /** Automatic downloads stop when less space than this is free. */
    const val MIN_FREE_BYTES_FOR_AUTOMATIC_DOWNLOAD = 2L * 1024 * 1024 * 1024

    /** Queues the download of a track. The work waits for an unmetered network unless [wifiOnly] is false. */
    fun enqueue(context: Context, track: Track, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf("track" to JsonCodec.encodeTrack(track).toString()))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .addTag("audio-download").addTag("track:${track.id}").build()
        WorkManager.getInstance(context).enqueueUniqueWork("download:${track.id}", ExistingWorkPolicy.KEEP, request)
    }

    /** Whether the free storage allows an automatic download. */
    fun hasRoom(app: OpenTuneApp): Boolean = app.filesDir.usableSpace >= MIN_FREE_BYTES_FOR_AUTOMATIC_DOWNLOAD

    /**
     * Downloads a track that just counted as played, when the listener turned that option on. A local file, a track that is on the device
     * already and every case without an account or with offline mode on are left out.
     */
    fun afterPlay(app: OpenTuneApp, track: Track) {
        val settings = app.settings.state.value
        if (!settings.downloadPlayed || settings.offline || !app.tidal.signedIn || track.isFile || !hasRoom(app)) return
        if (app.library.get(track.id)?.onDevice == true) return
        app.library.save(track)
        enqueue(app, track, settings.downloadOnWifi)
    }
}
