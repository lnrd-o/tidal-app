package io.github.yyan115.opentune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yyan115.opentune.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The pages that hold a licence text, with the asset file of each. */
val LicenceDocuments: Map<SettingsPage, String> = linkedMapOf(
    SettingsPage.Apache to "Apache-2.0.txt",
    SettingsPage.Inter to "Inter-OFL.txt", SettingsPage.Serif to "Instrument-Serif-OFL.txt", SettingsPage.Space to "Space-Grotesk-OFL.txt", SettingsPage.Mono to "JetBrains-Mono-OFL.txt",
)

/** A component of the list. [project] is empty for OpenTune itself. */
class LicenceEntry(val name: String, val project: String, val licence: Int, val page: SettingsPage)

private val FontPages = mapOf("Inter" to SettingsPage.Inter, "Instrument Serif" to SettingsPage.Serif, "Space Grotesk" to SettingsPage.Space, "JetBrains Mono" to SettingsPage.Mono)

/**
 * Reads the components from the table of `THIRD_PARTY_NOTICES.txt`: the component, a link to its project and its licence.
 * OpenTune comes first, with its own licence.
 */
fun licenceEntries(notices: String, appName: String): List<LicenceEntry> {
    val link = Regex("\\[([^]]+)]\\(([^)]+)\\)")
    val components = notices.lines().filter { it.startsWith("| ") }.drop(2).mapNotNull { row ->
        val cells = row.trim().trim('|').split('|').map(String::trim)
        if (cells.size != 3) return@mapNotNull null
        val project = link.find(cells[1])?.groupValues?.get(1) ?: cells[1]
        when (cells[2]) {
            "Apache-2.0" -> LicenceEntry(cells[0], project, R.string.licenses_apache, SettingsPage.Apache)
            "OFL-1.1" -> FontPages[cells[0]]?.let { LicenceEntry(cells[0], project, R.string.licenses_ofl, it) }
            else -> null
        }
    }
    return listOf(LicenceEntry(appName, "", R.string.licenses_apache, SettingsPage.Apache)) + components
}

/** The components that OpenTune ships, each with its licence. A row opens the text of the licence. */
@Composable
fun LicencesPage(onOpen: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val appName = stringResource(R.string.brand_name)
    val entries by produceState<List<LicenceEntry>?>(null) {
        value = withContext(Dispatchers.IO) { licenceEntries(context.assets.open("licenses/THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() }, appName) }
    }
    val list = entries
    if (list == null) CircularProgressIndicator(Modifier.padding(24.dp))
    else LazyColumn(Modifier.fillMaxSize().testTag("licences-list"), contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list) { entry ->
            val licence = stringResource(entry.licence)
            SettingRow(OpenTuneIcons.Description, entry.name, if (entry.project.isEmpty()) licence else stringResource(R.string.licenses_entry, entry.project, licence),
                { onOpen(entry.page) }, trailing = { Icon(OpenTuneIcons.ChevronRight, null) })
        }
    }
}

/** The text of one licence, which can be selected. */
@Composable
fun LicenceDocumentPage(page: SettingsPage) {
    val context = LocalContext.current
    val file = LicenceDocuments.getValue(page)
    val text by produceState<String?>(null, file) { value = withContext(Dispatchers.IO) { context.assets.open("licenses/$file").bufferedReader().use { it.readText() } } }
    Column(Modifier.fillMaxSize().testTag("licence-document").verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
        val document = text
        if (document == null) CircularProgressIndicator(Modifier.padding(top = 16.dp))
        else SelectionContainer { Text(document, style = MaterialTheme.typography.bodySmall) }
    }
}
