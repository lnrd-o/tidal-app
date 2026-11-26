package io.github.lnrdo.mytidal.playback

import io.github.lnrdo.mytidal.core.ServiceException
import io.github.lnrdo.mytidal.core.Problem
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.upstream.Allocator
import io.github.lnrdo.mytidal.OpenTuneApp
import io.github.lnrdo.mytidal.core.JsonCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException

/** Resolve signed stream URLs when a queue item is prepared, rather than for the whole queue. */
@UnstableApi
class ResolvingSource(private val app: OpenTuneApp, private val original: MediaItem,
    private val drmProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider? = null,
    private val loadPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy? = null) : CompositeMediaSource<Unit>() {
    private var child: MediaSource? = null
    private var failure: IOException? = null
    private var scope: CoroutineScope? = null
    override fun getMediaItem(): MediaItem = original

    private companion object { const val LOAD_RETRIES = 8 }

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        val handler = Handler(android.os.Looper.myLooper()!!)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { owner ->
            owner.launch {
                try {
                    val encoded = original.mediaMetadata.extras?.getString("track")
                    val track = encoded?.let { JsonCodec.decodeTrack(JSONObject(it)) } ?: app.library.get(original.mediaId)
                        ?: throw ServiceException(0, "This track is no longer in the library.", Problem.TRACK_MISSING)
                    val resolved = app.sources.resolve(track)
                    val offlineCopy = app.library.get(track.id)?.localUri?.startsWith("opentune-offline:") == true
                    handler.post {
                        if (scope === owner) {
                            val factory = DefaultMediaSourceFactory(app.sources.dataSource(app.settings.state.value.offline || offlineCopy || track.localUri?.startsWith("opentune-offline:") == true, track.id))
                            drmProvider?.let(factory::setDrmSessionManagerProvider)
                            factory.setLoadErrorHandlingPolicy(loadPolicy ?: androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(LOAD_RETRIES))
                            val source = factory.createMediaSource(resolved)
                            // Preparation may immediately publish a timeline and request a period.
                            child = source
                            prepareChildSource(Unit, source)
                        }
                    }
                } catch (error: Exception) {
                    handler.post { if (scope === owner) failure = IOException(error.message ?: "The audio could not be loaded.", error) }
                }
            }
        }
    }

    override fun maybeThrowSourceInfoRefreshError() {
        failure?.let { throw it }
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun onChildSourceInfoRefreshed(id: Unit, mediaSource: MediaSource, timeline: Timeline) = refreshSourceInfo(timeline)
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        checkNotNull(child).createPeriod(id, allocator, startPositionUs)
    override fun releasePeriod(mediaPeriod: MediaPeriod) { child?.releasePeriod(mediaPeriod) }
    override fun releaseSourceInternal() {
        scope?.cancel(); scope = null; super.releaseSourceInternal(); child = null; failure = null
    }
}

@UnstableApi
class OpenTuneSourceFactory(private val app: OpenTuneApp) : MediaSource.Factory {
    private var drmProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider? = null
    private var loadPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy? = null
    override fun setDrmSessionManagerProvider(provider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider): MediaSource.Factory = apply { drmProvider = provider }
    override fun setLoadErrorHandlingPolicy(policy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy): MediaSource.Factory = apply { loadPolicy = policy }
    override fun createMediaSource(mediaItem: MediaItem): MediaSource = ResolvingSource(app, mediaItem, drmProvider, loadPolicy)
    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_DASH)
}
