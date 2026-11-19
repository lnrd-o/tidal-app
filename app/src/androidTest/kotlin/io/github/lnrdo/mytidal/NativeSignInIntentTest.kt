package io.github.lnrdo.mytidal

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeSignInIntentTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun handled(address: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(address)).addCategory(Intent.CATEGORY_BROWSABLE)
        return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .any { it.activityInfo.packageName == context.packageName && it.activityInfo.name == MainActivity::class.java.name }
    }

    @Test fun theBrowserCanReturnToOpenTuneThroughTheRegisteredCallback() {
        assertTrue(handled("tidal://login/auth?code=test&state=test"))
        assertFalse(handled("tidal://track/42"))
        assertFalse(handled("tidal://login/auth/other?code=test"))
        assertFalse(handled("https://tidal.com/android/login/auth?code=test"))
    }

    @Test fun launcherLabelAndApplicationClassUseTheBrand() {
        assertEquals("io.github.lnrdo.mytidal", context.packageName)
        assertEquals("OpenTune Debug", context.applicationInfo.loadLabel(context.packageManager).toString())
        assertEquals(OpenTuneApp::class.java.name, context.applicationInfo.className)
        assertNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
    }
}
