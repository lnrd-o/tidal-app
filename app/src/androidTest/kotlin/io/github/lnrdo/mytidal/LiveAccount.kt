package io.github.lnrdo.mytidal

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lnrdo.mytidal.core.Session
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.io.FileOutputStream

/** Shared setup for the opt-in checks that use a real TIDAL account. Nothing here runs in CI. */
object LiveAccount {
    private var supplied: Session? = null
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenTuneApp

    /** Signs the app in with the session pushed to `files/live-test-session.json`, and restores the previous state afterwards. */
    fun rule() = TestRule { base: Statement, _: Description -> object : Statement() {
        override fun evaluate() {
            assumeTrue("Live account tests require explicit opt-in", InstrumentationRegistry.getArguments().getString("liveAccount") == "true")
            if (supplied == null) {
                val file = File(app.filesDir, "live-test-session.json")
                if (file.isFile) try {
                    val json = JSONObject(file.readText())
                    supplied = Session(json.getString("access_token"), json.getString("refresh_token"),
                        (json.getDouble("expires_at") * 1000).toLong(), json.getString("userId"), json.getString("countryCode"))
                } finally { file.delete() }
                else supplied = app.sessions.read()
                assumeTrue("Sign in to the debug app before running live-account checks", supplied != null)
            }
            val previous = app.sessions.read()
            app.tidal.signOut()
            app.sessions.write(requireNotNull(supplied))
            try { base.evaluate() }
            finally {
                // `-e keepSession true` leaves the account signed in for manual checks on a development device.
                if (InstrumentationRegistry.getArguments().getString("keepSession") != "true") {
                    app.tidal.signOut()
                    previous?.let(app.sessions::write)
                }
            }
        }
    } }

    /** Saves the whole screen, including dialogs, under the app's external files folder. */
    fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = requireNotNull(instrumentation.targetContext.getExternalFilesDir("live-screens"))
        folder.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        FileOutputStream(File(folder, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
