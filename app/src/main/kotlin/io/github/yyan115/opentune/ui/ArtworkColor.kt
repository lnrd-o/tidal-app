package io.github.yyan115.opentune.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import io.github.yyan115.opentune.core.DominantColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Finds the colour of a cover, once for each cover. */
private object ArtworkColors {
    private val cache = object : LinkedHashMap<String, Int?>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int?>) = size > 64
    }

    suspend fun of(context: Context, url: String): Int? {
        synchronized(cache) { if (cache.containsKey(url)) return cache[url] }
        val found = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context).data(url).size(Size(48, 48)).allowHardware(false).build()
                val result = SingletonImageLoader.get(context).execute(request)
                if (result !is SuccessResult) return@runCatching null
                val bitmap = result.image.toBitmap().let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                DominantColor.of(pixels)
            }.getOrNull()
        }
        synchronized(cache) { cache[url] = found }
        return found
    }
}

/**
 * The accent of the player: the main colour of the cover when [enabled], else white. It moves from one colour to the next as the cover changes.
 * A cover without colour (black and white) keeps the accent white.
 */
@Composable
fun rememberArtworkAccent(url: String?, enabled: Boolean): Color {
    val context = LocalContext.current.applicationContext
    var target by remember { mutableStateOf(Color.White) }
    LaunchedEffect(url, enabled) {
        target = if (!enabled || url.isNullOrBlank()) Color.White else ArtworkColors.of(context, url)?.let { Color(it) } ?: Color.White
    }
    return animateColorAsState(target, tween(450), label = "artwork-accent").value
}
