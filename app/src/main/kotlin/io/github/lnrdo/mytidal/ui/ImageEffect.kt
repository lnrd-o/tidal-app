package io.github.lnrdo.mytidal.ui

import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import io.github.lnrdo.mytidal.core.effects.DotShape
import io.github.lnrdo.mytidal.core.effects.EffectOptions
import io.github.lnrdo.mytidal.core.effects.ImageEffects
import io.github.lnrdo.mytidal.core.effects.ImageStyle
import io.github.lnrdo.mytidal.data.Settings

/** The effect that the whole app is drawn through, from the settings. NONE draws it as it is. */
val LocalImageEffect = staticCompositionLocalOf { EffectOptions() }

/** The options of the effect from the settings, with the duotone made readable (WCAG AA) for the text that is drawn through it. */
fun Settings.effectOptions(): EffectOptions {
    val (shadow, light) = ImageEffects.readable(imageShadow, imageLight)
    return EffectOptions(ImageStyle.of(imageStyle), imageCell, imageContrast, imageBrightness, imageAngle, DotShape.of(imageShape), imageDuotone, shadow, light).clamped()
}

/** Whether this phone can draw the whole app through an effect: it needs runtime shaders, from Android 13. */
val canDrawEffects: Boolean get() = Build.VERSION.SDK_INT >= 33

/**
 * Draws this content through the effect of [LocalImageEffect], on the GPU: each mode is a shader that reads the content at the pixel (dither), at the
 * centre of its cell (pixel) or at 3×3 points round the centre of its turned cell (halftone). It works at the resolution of the screen, and the cell size
 * is in density-independent pixels, so it looks the same on every screen. The effect is made again only when the options or the density change.
 */
@Composable
fun Modifier.appEffect(): Modifier {
    val options = LocalImageEffect.current
    if (options.style == ImageStyle.NONE || !canDrawEffects) return this
    val density = LocalDensity.current.density
    // A shader that the GPU driver will not build leaves the app as it is rather than taking it down.
    val effect = remember(options, density) {
        runCatching { EffectShaders.effect(options, density) }.onFailure { android.util.Log.w("OpenTune", "image effect shader failed", it) }.getOrNull()
    } ?: return this
    return this.graphicsLayer { renderEffect = effect }
}

/** The shaders of the modes, written in AGSL. They compute what [ImageEffects] computes, pixel for pixel. */
@RequiresApi(33)
internal object EffectShaders {
    private const val COMMON = """
        uniform shader content;
        uniform float cell;
        uniform float contrast;
        uniform float bright;
        uniform float duo;
        layout(color) uniform half4 shadow;
        layout(color) uniform half4 light;
        float luma(half4 c) {
            float l = dot(float3(c.rgb), float3(0.2126, 0.7152, 0.0722));
            return clamp((l - 0.5) * contrast + 0.5 + bright, 0.0, 1.0);
        }
        bool inside(float dx, float dy, float r, float shape) {
            if (shape < 0.5) return dx * dx + dy * dy <= r * r;
            if (shape < 1.5) return max(abs(dx), abs(dy)) <= r * 0.886;
            return abs(dx) + abs(dy) <= r * 1.2533;
        }
    """

    // Ordered dither: the Bayer matrix by arithmetic (b2(x, y) = 2|x − y| + y over bits), sampled in grains of a quarter of the cell.
    private const val DITHER = COMMON + """
        float b2(float x, float y) { return 2.0 * abs(x - y) + y; }
        half4 main(float2 p) {
            half4 c = content.eval(p);
            float grain = max(1.0, cell / 4.0);
            float gx = floor(p.x / grain);
            float gy = floor(p.y / grain);
            float x0 = mod(gx, 2.0); float y0 = mod(gy, 2.0);
            float x1 = mod(floor(gx / 2.0), 2.0); float y1 = mod(floor(gy / 2.0), 2.0);
            float t = (4.0 * b2(x0, y0) + b2(x1, y1) + 0.5) / 16.0;
            bool on = luma(c) > t;
            if (duo > 0.5) return on ? light : shadow;
            return on ? half4(c.rgb, 1.0) : shadow;
        }
    """

    // Halftone: into the turned grid, the cell and its centre, 3×3 samples round the centre, and a hard dot whose area follows the brightness.
    private const val HALFTONE = COMMON + """
        uniform float cs;
        uniform float sn;
        uniform float shape;
        half4 main(float2 p) {
            float u = p.x * cs + p.y * sn;
            float v = -p.x * sn + p.y * cs;
            float cu = (floor(u / cell) + 0.5) * cell;
            float cv = (floor(v / cell) + 0.5) * cell;
            float sum = 0.0;
            half4 col = half4(0);
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    float su = cu + float(i) * cell / 3.0;
                    float sv = cv + float(j) * cell / 3.0;
                    half4 c = content.eval(float2(su * cs - sv * sn, su * sn + sv * cs));
                    sum += luma(c);
                    col += c;
                }
            }
            float b = sum / 9.0;
            float r = 0.72 * cell * sqrt(b);
            if (!inside(u - cu, v - cv, r, shape)) return shadow;
            return duo > 0.5 ? light : half4((col / 9.0).rgb, 1.0);
        }
    """

    // The mosaic: each block takes the colour of 3×3 samples in it and draws a smaller square or dot on a dark grid.
    private const val PIXEL = COMMON + """
        uniform float shape;
        half4 main(float2 p) {
            float2 block = floor(p / cell);
            float2 centre = (block + 0.5) * cell;
            half4 col = half4(0);
            for (int j = -1; j <= 1; j++) {
                for (int i = -1; i <= 1; i++) {
                    col += content.eval(centre + float2(float(i), float(j)) * cell / 3.0);
                }
            }
            col = col / 9.0;
            half4 grid = mix(half4(0, 0, 0, 1), shadow, 0.35);
            float hs = cell * 0.78 / 2.0;
            float dx = p.x - centre.x;
            float dy = p.y - centre.y;
            bool hit = shape > 0.5 && shape < 1.5 ? (abs(dx) <= hs && abs(dy) <= hs) : inside(dx, dy, hs, shape);
            if (!hit) return grid;
            if (duo > 0.5) return mix(shadow, light, luma(col));
            return half4(col.rgb, 1.0);
        }
    """

    private val dither by lazy { RuntimeShader(DITHER) }
    private val halftone by lazy { RuntimeShader(HALFTONE) }
    private val pixel by lazy { RuntimeShader(PIXEL) }

    fun effect(o: EffectOptions, density: Float): androidx.compose.ui.graphics.RenderEffect {
        val shader = when (o.style) {
            ImageStyle.HALFTONE -> halftone
            ImageStyle.PIXEL -> pixel
            else -> dither
        }
        shader.setFloatUniform("cell", o.cellSize * density)
        shader.setFloatUniform("contrast", o.contrast)
        shader.setFloatUniform("bright", o.brightness / 255f)
        shader.setFloatUniform("duo", if (o.duotone) 1f else 0f)
        shader.setColorUniform("shadow", o.shadow)
        shader.setColorUniform("light", o.light)
        if (o.style == ImageStyle.HALFTONE) {
            val a = Math.toRadians(o.gridAngle.toDouble())
            shader.setFloatUniform("cs", Math.cos(a).toFloat())
            shader.setFloatUniform("sn", Math.sin(a).toFloat())
        }
        if (o.style != ImageStyle.DITHER) shader.setFloatUniform("shape", o.shape.ordinal.toFloat())
        return RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

/**
 * The effect on a picture, on the CPU, for the preview in the settings (it runs on every phone): the bitmap is read at the size it is shown, capped at
 * 1600 pixels on its longest side, drawn by [ImageEffects] and given back. The same bitmap and options always give the same pixels.
 */
fun Bitmap.withEffect(options: EffectOptions, density: Float): Bitmap {
    val longest = maxOf(width, height)
    val source = if (longest > 1600) Bitmap.createScaledBitmap(this, width * 1600 / longest, height * 1600 / longest, false) else this
    val software = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
    val w = software.width; val h = software.height
    val pixels = IntArray(w * h)
    software.getPixels(pixels, 0, w, 0, 0, w, h)
    val scaled = options.copy(cellSize = (options.cellSize * density).toInt().coerceAtLeast(1))
    val out = ImageEffects.render(pixels, w, h, scaled)
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}
