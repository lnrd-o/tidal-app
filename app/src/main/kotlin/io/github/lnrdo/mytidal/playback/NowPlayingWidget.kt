package io.github.lnrdo.mytidal.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.view.KeyEvent
import android.widget.RemoteViews
import io.github.lnrdo.mytidal.MainActivity
import io.github.lnrdo.mytidal.R
import java.io.File

/** What the home screen widget shows. The playback service writes it, and the widget only reads it. */
object WidgetState {
    private const val FILE = "widget"
    private fun preferences(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun artwork(context: Context) = File(context.filesDir, "widget-artwork.png")

    data class Snapshot(val title: String, val artist: String, val playing: Boolean)

    fun read(context: Context): Snapshot? = preferences(context).let { values ->
        values.getString("title", null)?.let { Snapshot(it, values.getString("artist", "").orEmpty(), values.getBoolean("playing", false)) }
    }

    fun write(context: Context, snapshot: Snapshot?) {
        preferences(context).edit().apply {
            if (snapshot == null) clear() else { putString("title", snapshot.title); putString("artist", snapshot.artist); putBoolean("playing", snapshot.playing) }
        }.apply()
    }

    /** Saves a small copy of the cover, or removes it when the track has none. */
    fun saveArtwork(context: Context, bytes: ByteArray?) {
        val target = artwork(context)
        if (bytes == null) { target.delete(); return }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ARTWORK_SIZE) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
        bitmap.recycle()
    }

    fun artworkBitmap(context: Context) = artwork(context).takeIf(File::isFile)?.let { BitmapFactory.decodeFile(it.path) }
    private const val ARTWORK_SIZE = 192
}

@androidx.media3.common.util.UnstableApi
class NowPlayingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views(context)) }
    }

    companion object {
        /** Redraws every widget on the home screen. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NowPlayingWidget::class.java))
            if (ids.isNotEmpty()) ids.forEach { manager.updateAppWidget(it, views(context)) }
        }

        private fun mediaButton(context: Context, code: Int): PendingIntent = PendingIntent.getService(context, code,
            Intent(Intent.ACTION_MEDIA_BUTTON).setClass(context, PlaybackService::class.java).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, code)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        fun views(context: Context): RemoteViews {
            val snapshot = WidgetState.read(context)
            return RemoteViews(context.packageName, R.layout.widget_now_playing).apply {
                setTextViewText(R.id.widget_title, snapshot?.title ?: context.getString(R.string.app_name))
                setTextViewText(R.id.widget_artist, snapshot?.artist?.ifBlank { null } ?: if (snapshot == null) context.getString(R.string.widget_nothing_playing) else "")
                WidgetState.artworkBitmap(context)?.let { setImageViewBitmap(R.id.widget_artwork, it) } ?: setImageViewResource(R.id.widget_artwork, R.drawable.ic_launcher_foreground)
                setImageViewResource(R.id.widget_play, if (snapshot?.playing == true) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
                setOnClickPendingIntent(R.id.widget_previous, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                setOnClickPendingIntent(R.id.widget_play, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                setOnClickPendingIntent(R.id.widget_next, mediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT))
                val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                setOnClickPendingIntent(R.id.widget_artwork, open)
                setOnClickPendingIntent(R.id.widget_title, open)
                setOnClickPendingIntent(R.id.widget_artist, open)
            }
        }
    }
}
