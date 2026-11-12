package io.github.yyan115.opentune.ui

import io.github.yyan115.opentune.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yyan115.opentune.core.Contributor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsSheet(credits: CreditsUi, onClose: () -> Unit, onPerson: (Contributor) -> Unit) {
    ModalBottomSheet(shape = androidx.compose.ui.graphics.RectangleShape, dragHandle = { SheetHandle() }, onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            item(key = "title") {
                Text(stringResource(R.string.credits_title), style = AppFonts.title(32, 38))
                Text(credits.title, Modifier.padding(top = 4.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (credits.loading) item(key = "loading") { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            credits.error?.let { error -> item(key = "error") { Text(error, color = MaterialTheme.colorScheme.error) } }
            if (!credits.loading && credits.error == null && credits.credits.isEmpty()) item(key = "empty") {
                Text(stringResource(R.string.credits_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(credits.credits, key = { it.role }) { credit ->
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    UpperLabel(credit.role)
                    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        credit.people.forEach { person ->
                            if (person.id.isNotBlank()) Text(person.name, Modifier.clickable { onPerson(person) }.padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            else Text(person.name, Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
