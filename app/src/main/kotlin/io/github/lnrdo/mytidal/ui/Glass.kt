package io.github.lnrdo.mytidal.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * What lies behind a blurred pane: a layer that the content under the panes draw, and where that layer sits on the screen. A pane draws the
 * part of it that is under itself, blurred. The layer holds references to what was drawn, so content that scrolls or animates under the glass shows through
 * the blur without being recorded again.
 */
@Stable
class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

/** The backdrop that blurred panes in this part of the screen blur, or null where there is none (in a dialog, or before Android 12). */
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** Makes this content the backdrop of the panes above it: it is drawn as usual, and kept in [backdrop] for them to blur. */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

/**
 * A blurred pane: the backdrop under it, blurred by [blur], seen through a tint of the surface ([fill], 0 clear to 1 opaque). Nothing else: no edge, no
 * sheen. Only the pane's own area is blurred, a little larger so that its edge has something to sample, which keeps it cheap. Without a backdrop, or before
 * Android 12, the pane is a plain translucent tint.
 */
@Composable
fun Modifier.blurPane(shape: Shape, fill: Float = .5f, blur: Dp = 26.dp): Modifier {
    val dark = LocalAppTheme.current.dark
    val surface = MaterialTheme.colorScheme.surface
    val backdrop = LocalBackdrop.current?.takeIf { Build.VERSION.SDK_INT >= 31 }
    val body = when {
        backdrop != null && dark -> surface.copy(alpha = .22f + .38f * fill)
        backdrop != null -> surface.copy(alpha = .35f + .40f * fill)
        dark -> surface.copy(alpha = .70f + .25f * fill)
        else -> surface.copy(alpha = .75f + .20f * fill)
    }
    val panel = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Zero) }
    val blurred = if (backdrop == null) Modifier else Modifier
        .onGloballyPositioned { position = it.positionInRoot() }
        .drawBehind {
            val radius = blur.toPx()
            val pad = (radius * 2).toInt()
            panel.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
            panel.topLeft = IntOffset(-pad, -pad)
            val shift = backdrop.origin - position
            panel.record(IntSize(size.width.toInt() + pad * 2, size.height.toInt() + pad * 2)) {
                translate(shift.x + pad, shift.y + pad) { drawLayer(backdrop.layer) }
            }
            drawLayer(panel)
        }
    return this.clip(shape).then(blurred).background(body)
}
