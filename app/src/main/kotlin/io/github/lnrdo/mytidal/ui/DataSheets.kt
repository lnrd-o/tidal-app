package io.github.lnrdo.mytidal.ui

import io.github.lnrdo.mytidal.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import io.github.lnrdo.mytidal.data.Settings
import kotlinx.coroutines.launch

@UnstableApi
@Composable
fun ScrobbleDialog(settings: Settings, vm: MainViewModel, onClose: () -> Unit) {
    var enabled by remember { mutableStateOf(settings.scrobble) }
    var server by remember { mutableStateOf(settings.scrobbleServer) }
    var token by remember { mutableStateOf(vm.scrobbleToken()) }
    var result by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.scrobble_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.scrobble_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.scrobble_send), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(enabled, { enabled = it })
            }
            OutlinedTextField(server, { server = it; result = null }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.scrobble_server)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(token, { token = it; result = null }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.scrobble_token)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), supportingText = { Text(stringResource(R.string.scrobble_token_hint)) })
            TextButton(onClick = { checking = true; scope.launch { result = vm.checkScrobbling(server, token); checking = false } }, enabled = !checking && token.isNotBlank()) {
                if (checking) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp) else Text(stringResource(R.string.scrobble_check))
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = { vm.saveScrobbling(enabled && token.isNotBlank(), server, token); onClose() }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) } })
}

data class ListeningStats(val plays: Int, val hours: Long, val minutes: Long, val tracks: List<io.github.lnrdo.mytidal.data.LibraryStore.PlayCount>, val artists: List<Pair<String, Int>>)
