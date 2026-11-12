package io.github.yyan115.opentune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yyan115.opentune.R

/** The first screen. The app plays TIDAL, so it asks for the sign-in. */
@Composable
fun WelcomeScreen(onSignIn: () -> Unit) {
    Surface(Modifier.fillMaxSize().testTag("welcome"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Icon(OpenTuneIcons.Brand, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.brand_name), Modifier.padding(top = 20.dp), style = AppFonts.title(52, 58), textAlign = TextAlign.Center)
            Text(stringResource(R.string.welcome_tagline), Modifier.padding(top = 12.dp).padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            TuneButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 52.dp).testTag("welcome-sign-in")) { Text(stringResource(R.string.login_button)) }
            // One sentence for each line, both in the largest size at which the longer one fits.
            WelcomeNote(listOf(stringResource(R.string.welcome_note), stringResource(R.string.welcome_risk)), Modifier.padding(top = 16.dp))
        }
    }
}

/** Lines of small text that each stay on one line. They share one font size, from 12 sp down to 8 sp, so that the longest line fits the width. */
@Composable
private fun WelcomeNote(lines: List<String>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val base = MaterialTheme.typography.bodySmall
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val size = remember(lines, width, base) {
            generateSequence(12f) { it - .5f }.takeWhile { it >= 8f }
                .firstOrNull { size -> lines.all { measurer.measure(it, base.copy(fontSize = size.sp), maxLines = 1, softWrap = false).size.width <= width } } ?: 8f
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            lines.forEach { Text(it, style = base.copy(fontSize = size.sp), maxLines = 1, softWrap = false, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
