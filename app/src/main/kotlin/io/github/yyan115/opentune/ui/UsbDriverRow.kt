package io.github.yyan115.opentune.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yyan115.opentune.R
import io.github.yyan115.opentune.playback.usb.UsbDac

/** The switch of the app's own USB driver, with what it is doing now: no DAC, waiting for permission, ready, or playing at a rate and bit depth. */
@Composable
fun UsbDriverRow(on: Boolean, onChange: (Boolean) -> Unit) {
    val dac by UsbDac.state.collectAsStateWithLifecycle()
    Column {
        SwitchRow(OpenTuneIcons.Waves, stringResource(R.string.settings_usb_driver), on, onChange, stringResource(R.string.settings_usb_driver_text))
        if (on) {
            val name = dac.name
            val line = when {
                name == null -> stringResource(R.string.settings_usb_none)
                !dac.allowed -> stringResource(R.string.settings_usb_waiting, name)
                dac.playing != null -> stringResource(R.string.settings_usb_playing, name, dac.playing!!)
                else -> stringResource(R.string.settings_usb_ready, name)
            }
            Text(line, Modifier.padding(start = 72.dp, end = 24.dp, bottom = 10.dp), fontFamily = AppFonts.mono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
