package io.github.yyan115.opentune

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import io.github.yyan115.opentune.ui.MainViewModel
import io.github.yyan115.opentune.ui.OpenTuneRoot
import io.github.yyan115.opentune.ui.OpenTuneTheme
import io.github.yyan115.opentune.ui.enterVideoPip
import io.github.yyan115.opentune.ui.LocalNetworkEpoch
import io.github.yyan115.opentune.ui.LocalOfflineMode
import io.github.yyan115.opentune.ui.LocalPlaybackPosition
import io.github.yyan115.opentune.core.SignInCallback

@UnstableApi
class MainActivity : ComponentActivity() {
    private var incomingCallback by mutableStateOf<String?>(null)
    private var incomingSearch by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptIntent(intent)
        enableEdgeToEdge()
        setContent {
            val model: MainViewModel = viewModel()
            val state by model.state.collectAsStateWithLifecycle()
            val networkEpoch by (application as OpenTuneApp).networkEpoch.collectAsStateWithLifecycle()
            LaunchedEffect(incomingSearch) { incomingSearch?.let { model.playFromSearch(it); incomingSearch = null } }
            LaunchedEffect(incomingCallback) {
                incomingCallback?.let { model.completeBrowserLogin(it); incomingCallback = null }
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
            }
            OpenTuneTheme { CompositionLocalProvider(LocalOfflineMode provides state.settings.offline, LocalNetworkEpoch provides networkEpoch, LocalPlaybackPosition provides model.position) { OpenTuneRoot(model, state) } }
        }
    }

    /** Keeps a music video visible in a small window when the listener leaves the app. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java].state.value.video != null) enterVideoPip()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); acceptIntent(intent) }

    private fun acceptIntent(incoming: Intent) {
        if (incoming.action == "android.media.action.MEDIA_PLAY_FROM_SEARCH") incomingSearch = incoming.getStringExtra(android.app.SearchManager.QUERY)
        if (incoming.action == Intent.ACTION_VIEW) {
            incoming.dataString?.takeIf(SignInCallback::supported)?.let { incomingCallback = it }
            // Keep the launch action intact so lifecycle observers can identify this activity.
            // Remove only the callback data so one-time codes do not remain in its intent.
            incoming.data = null
        }
        setIntent(incoming)
    }
}
