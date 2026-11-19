package io.github.lnrdo.mytidal.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.lnrdo.mytidal.R

private class Entry(val permission: String, @StringRes val title: Int, @StringRes val text: Int, val icon: ImageVector)

/** The page after the first screen. It asks for the permissions that the app needs on this version of Android, and each one can be skipped. */
@Composable
fun SetupScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val entries = buildList {
        if (Permissions.notificationsApply()) add(Entry(Manifest.permission.POST_NOTIFICATIONS, R.string.setup_notifications, R.string.setup_notifications_text, OpenTuneIcons.Notifications))
        if (Permissions.storageApplies()) add(Entry(Manifest.permission.WRITE_EXTERNAL_STORAGE, R.string.setup_storage, R.string.setup_storage_text, OpenTuneIcons.Folder))
    }
    Surface(Modifier.fillMaxSize().testTag("setup"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
            Spacer(Modifier.height(48.dp))
            Text(stringResource(R.string.setup_title), Modifier.padding(horizontal = 24.dp), style = AppFonts.title(44, 50))
            Spacer(Modifier.height(24.dp))
            entries.forEach { entry ->
                val allowed = remember(refresh) { Permissions.granted(context, entry.permission) }
                SettingRow(entry.icon, stringResource(entry.title), stringResource(entry.text), trailing = {
                    if (allowed) Row(verticalAlignment = Alignment.CenterVertically) { Icon(OpenTuneIcons.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.setup_allowed), color = MaterialTheme.colorScheme.primary) }
                    else TextButton(onClick = { launcher.launch(entry.permission) }, modifier = Modifier.testTag("setup-allow")) { Text(stringResource(R.string.setup_allow)) }
                })
            }
            Spacer(Modifier.weight(1f))
            TuneButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 52.dp).testTag("setup-continue")) { Text(stringResource(R.string.setup_continue)) }
        }
    }
}
