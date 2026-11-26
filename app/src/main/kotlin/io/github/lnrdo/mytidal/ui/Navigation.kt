package io.github.lnrdo.mytidal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.lnrdo.mytidal.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The places that the navigation offers. A listener who has not signed in has [Device] only, so the navigation is not shown. */
enum class Destination(val tag: String, @androidx.annotation.StringRes val title: Int) {
    Home("home", R.string.nav_home), Search("search", R.string.nav_search), Library("library", R.string.nav_library), Mixes("mixes", R.string.nav_mixes), Device("device", R.string.nav_device);

    val icon: ImageVector get() = when (this) {
        Home -> OpenTuneIcons.Headphones; Search -> OpenTuneIcons.Search; Library -> OpenTuneIcons.LibraryMusic; Mixes -> OpenTuneIcons.Djai; Device -> OpenTuneIcons.Device
    }
}

@Composable
fun OpenTuneRail(destinations: List<Destination>, selected: Destination, onSelect: (Destination) -> Unit) {
    Column(Modifier.width(84.dp).fillMaxHeight().safeDrawingPadding().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        destinations.forEach { destination ->
            val color = if (selected == destination) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Column(Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("nav-${destination.tag}").selectable(selected == destination,
                role = Role.Tab, onClick = { onSelect(destination) }), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(destination.icon, null, Modifier.size(22.dp), tint = color)
                Text(stringResource(destination.title), Modifier.padding(top = 6.dp), fontFamily = AppFonts.body, fontSize = 9.sp, maxLines = 1, color = color)
            }
        }
    }
}

@Composable
fun OpenTuneNavigation(destinations: List<Destination>, selected: Destination, onSelect: (Destination) -> Unit) {
    // In the rounded design the bar is a pill of glass that floats over the foot of the page, and the page that is open sits in a lens of its own.
    val round = LocalRound.current
    val pill = androidx.compose.foundation.shape.RoundedCornerShape(30.dp)
    Row(Modifier.fillMaxWidth().navigationBarsPadding().then(if (round) Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .92f), pill).blurPane(pill, fill = .4f).padding(horizontal = 6.dp, vertical = 4.dp)
        else Modifier.padding(horizontal = 12.dp, vertical = 4.dp)), horizontalArrangement = Arrangement.SpaceAround) {
        destinations.forEach { destination ->
            val active = destination == selected
            Column(Modifier.weight(1f).heightIn(min = 58.dp).testTag("nav-${destination.tag}")
                .selectable(active, role = Role.Tab, onClick = { onSelect(destination) }),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                val indicator by androidx.compose.animation.core.animateFloatAsState(if (active) 1f else 0f,
                    if (round) androidx.compose.animation.core.spring(dampingRatio = .62f, stiffness = 520f) else androidx.compose.animation.core.tween(160), label = "nav-indicator")
                if (round) {
                    // The lens grows round the icon of the open page with a spring.
                    Box(Modifier.size(52.dp, 30.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.matchParentSize().graphicsLayer { scaleX = .6f + .4f * indicator; scaleY = .6f + .4f * indicator; alpha = indicator }
                            .blurPane(androidx.compose.foundation.shape.RoundedCornerShape(15.dp), fill = .5f))
                        Icon(destination.icon, null, Modifier.size(22.dp), tint = color)
                    }
                } else {
                    Box(Modifier.fillMaxWidth(.5f).height(2.dp).graphicsLayer { scaleX = indicator }.background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.height(8.dp))
                    Icon(destination.icon, null, Modifier.size(22.dp), tint = color)
                }
                Text(stringResource(destination.title), Modifier.padding(top = 6.dp), fontFamily = AppFonts.body, fontSize = 9.sp, lineHeight = 13.sp, maxLines = 1,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal, color = color)
            }
        }
    }
}

@Composable
fun FilterTab(selected: Boolean, onClick: () -> Unit, label: String) {
    Column(Modifier.width(IntrinsicSize.Max).selectable(selected, role = Role.Tab, onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontFamily = AppFonts.body, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 1,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent))
    }
}
