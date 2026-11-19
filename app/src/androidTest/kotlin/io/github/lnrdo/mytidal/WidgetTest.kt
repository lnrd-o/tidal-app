package io.github.lnrdo.mytidal

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lnrdo.mytidal.playback.NowPlayingWidget
import io.github.lnrdo.mytidal.playback.WidgetState
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class WidgetTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @After fun reset() { WidgetState.write(context, null) }

    @Test fun theWidgetIsRegisteredAndShowsTheCurrentTrack() {
        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, NowPlayingWidget::class.java)
        val installed = manager.getInstalledProvidersForPackage(context.packageName, null)
        val info = installed.single { it.provider == provider }
        if (android.os.Build.VERSION.SDK_INT >= 31) assertEquals(4, info.targetCellWidth)

        WidgetState.write(context, WidgetState.Snapshot("Night Walk", "Playback test", playing = true))
        var rendered: Bitmap? = null
        instrumentation.runOnMainSync {
            val root = NowPlayingWidget.views(context).apply(context, FrameLayout(context))
            assertEquals("Night Walk", root.findViewById<TextView>(R.id.widget_title).text.toString())
            assertEquals("Playback test", root.findViewById<TextView>(R.id.widget_artist).text.toString())
            assertNotNull(root.findViewById<ImageButton>(R.id.widget_play))
            val density = context.resources.displayMetrics.density
            val width = (320 * density).toInt(); val height = (74 * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
        }
        val folder = requireNotNull(context.getExternalFilesDir("live-screens")).apply { mkdirs() }
        FileOutputStream(File(folder, "widget.png")).use { rendered!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        NowPlayingWidget.refresh(context)

        WidgetState.write(context, null)
        instrumentation.runOnMainSync {
            val root = NowPlayingWidget.views(context).apply(context, FrameLayout(context))
            assertEquals(context.getString(R.string.app_name), root.findViewById<TextView>(R.id.widget_title).text.toString())
            assertEquals("Nothing playing", root.findViewById<TextView>(R.id.widget_artist).text.toString())
        }
    }
}
