package io.github.yyan115.opentune.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yyan115.opentune.R
import io.github.yyan115.opentune.core.Track
import kotlinx.coroutines.launch

/** Previews the story card in each of the nine layouts and shares it. A swipe on the preview or a tap on a layout changes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryShareSheet(track: Track, quality: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var layout by remember { mutableStateOf(StoryLayout.CENTERED) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val strip = rememberLazyListState()
    LaunchedEffect(track.id, quality, layout) {
        bitmap = runCatching { TidalStoryRenderer.generateStoryCard(context, track, quality, layout) }.onFailure { android.util.Log.w("OpenTune", "Story card failed", it) }.getOrNull() ?: bitmap
    }
    LaunchedEffect(layout) { strip.animateScrollToItem(layout.ordinal.coerceAtMost(StoryLayout.entries.size - 1)) }
    val ready = bitmap != null
    val savedToast = stringResource(R.string.share_toast_saved)
    val linkToast = stringResource(R.string.share_toast_link_copied)
    fun share(action: (Context, android.net.Uri) -> Unit) {
        val card = bitmap ?: return
        scope.launch { action(context, TidalStoryRenderer.saveStoryToCache(context, card)); onClose() }
    }
    ModalBottomSheet(onDismissRequest = onClose, shape = RectangleShape, containerColor = Color(0xFF111111), contentColor = Color.White,
        dragHandle = { Box(Modifier.padding(vertical = 10.dp).size(32.dp, 3.dp).background(Color.White.copy(alpha = .3f))) }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            LazyRow(Modifier.fillMaxWidth().testTag("story-layouts"), state = strip, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(StoryLayout.entries) { item ->
                    val selected = item == layout
                    Text(stringResource(item.titleRes), Modifier.border(1.dp, if (selected) Color.White else Color.White.copy(alpha = .2f), RectangleShape)
                        .background(if (selected) Color.White else Color.Transparent).clickable(role = Role.Tab) { layout = item }.padding(horizontal = 14.dp, vertical = 8.dp),
                        fontFamily = AppFonts.body, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (selected) Color.Black else Color.White.copy(alpha = .75f))
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.height(400.dp).aspectRatio(9f / 16f).background(Color(0xFF0A0A0A)).border(1.dp, Color.White.copy(alpha = .14f), RectangleShape), contentAlignment = Alignment.Center) {
                Crossfade(bitmap, animationSpec = tween(180), label = "story-preview") { card ->
                    if (card != null) Image(card.asImageBitmap(), stringResource(R.string.share_story_preview_title), Modifier.fillMaxSize())
                }
                if (!ready) CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 1.5.dp)
            }
            Spacer(Modifier.height(16.dp))
            Button({ share { c, uri -> TidalStoryRenderer.shareToInstagramStories(c, uri, track) } }, Modifier.fillMaxWidth().height(50.dp), enabled = ready, shape = RectangleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                Icon(OpenTuneIcons.Instagram, null, Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.share_to_instagram), fontFamily = AppFonts.body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareAction(OpenTuneIcons.Share, stringResource(R.string.share_to_other_apps), ready, Modifier.weight(1f)) { share { c, uri -> TidalStoryRenderer.shareToGeneric(c, uri, track) } }
                ShareAction(OpenTuneIcons.SaveAlt, stringResource(R.string.share_save_to_device), ready, Modifier.weight(1f)) {
                    val card = bitmap ?: return@ShareAction
                    if (TidalStoryRenderer.saveStoryToGallery(context, card, track)) Toast.makeText(context, savedToast, Toast.LENGTH_SHORT).show()
                }
                ShareAction(OpenTuneIcons.ContentCopy, stringResource(R.string.share_copy_track_link), true, Modifier.weight(1f)) {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(track.title, "https://tidal.com/browse/track/${track.id}")) // i18n-ignore
                    Toast.makeText(context, linkToast, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Composable
private fun ShareAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier.height(44.dp), enabled = enabled, shape = RectangleShape, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .25f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White), contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(label, fontFamily = AppFonts.body, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
