package io.github.lnrdo.mytidal

import android.app.Notification
import android.app.NotificationManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import io.github.lnrdo.mytidal.core.AudioQuality
import io.github.lnrdo.mytidal.ui.MainViewModel
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in check against a real account: the notification of a download of a long track shows how far it got. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class DownloadNotificationLiveTest {
    @get:Rule(order = 0) val account = LiveAccount.rule()
    @get:Rule(order = 2) val notifications: androidx.test.rule.GrantPermissionRule = androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val model get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    @Test fun theNotificationFollowsTheProgressOfADownload() {
        compose.runOnIdle { model.settings { it.copy(offline = false, welcomeDone = true, downloadOnWifi = false, quality = AudioQuality.HI_RES_LOSSLESS) } }
        compose.runOnIdle { model.search("Pink Floyd Comfortably Numb") }
        compose.waitUntil(30_000) { model.state.value.search.tracks.any { it.durationMs >= 240_000 } }
        val track = model.state.value.search.tracks.first { it.durationMs >= 240_000 }
        val manager = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(NotificationManager::class.java)
        compose.runOnIdle { model.download(track) }
        val seen = sortedSetOf<Int>()
        val end = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < end) {
            manager.activeNotifications.filter { it.notification.channelId == "downloads" }.forEach {
                val extras = it.notification.extras
                if (!extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) && extras.getInt(Notification.EXTRA_PROGRESS_MAX) == 100) seen += extras.getInt(Notification.EXTRA_PROGRESS)
            }
            if (seen.size >= 3 && seen.max() >= 20) break
            Thread.sleep(300)
        }
        WorkManager.getInstance(InstrumentationRegistry.getInstrumentation().targetContext).cancelAllWork()
        compose.runOnIdle { model.removeLocal(track) }
        assertTrue("The notification showed these percentages: $seen", seen.size >= 3 && seen.max() >= 20)
    }
}
