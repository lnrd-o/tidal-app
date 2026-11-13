package io.github.yyan115.opentune.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yyan115.opentune.R

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(font: Int, vararg weights: Pair<Int, FontWeight>) = FontFamily(*weights.map { (value, weight) ->
    Font(font, weight, variationSettings = FontVariation.Settings(FontVariation.weight(value)))
}.toTypedArray())

private val DmSans by lazy {
    variable(R.font.dm_sans, 300 to FontWeight.Light, 400 to FontWeight.Normal, 500 to FontWeight.Medium, 600 to FontWeight.SemiBold, 700 to FontWeight.Bold)
}
private val Mono by lazy { variable(R.font.jetbrains_mono, 400 to FontWeight.Normal, 500 to FontWeight.Medium) }

/** The typefaces of the interface. Large titles use [display] at [displayScale] of the size that the screens ask for. */
class ThemeFonts(val body: FontFamily, val display: FontFamily, val mono: FontFamily, val displayWeight: FontWeight, val displayScale: Float, val displayTracking: TextUnit = 0.sp)

/** DM Sans everywhere: a formal, geometric sans-serif. Timecodes and technical readouts use a monospace face. */
val StudioFonts by lazy { ThemeFonts(DmSans, DmSans, Mono, FontWeight.SemiBold, .62f, (-0.4).sp) }

val LocalThemeFonts = staticCompositionLocalOf { StudioFonts }

/** The typefaces of the interface. Use these instead of naming a typeface. */
object AppFonts {
    val body: FontFamily @Composable @ReadOnlyComposable get() = LocalThemeFonts.current.body
    val display: FontFamily @Composable @ReadOnlyComposable get() = LocalThemeFonts.current.display
    val mono: FontFamily @Composable @ReadOnlyComposable get() = LocalThemeFonts.current.mono

    /** The style of a large title. [size] and [line] are design sizes that the theme scales down. */
    @Composable @ReadOnlyComposable
    fun title(size: Int, line: Int): TextStyle = LocalThemeFonts.current.let {
        TextStyle(fontFamily = it.display, fontWeight = it.displayWeight, fontSize = (size * it.displayScale).sp, lineHeight = (line * it.displayScale).sp, letterSpacing = it.displayTracking)
    }
}

/** How the backdrop of a theme is painted, and how its panels are made. */
enum class ThemeLook { Flat, Glass, Analog, Gold }

/** One complete look: colours, corner radius and typefaces. */
class AppTheme(
    val id: String, @StringRes val nameRes: Int, @StringRes val hintRes: Int, val dark: Boolean, val scheme: ColorScheme, val radius: Int, val fonts: ThemeFonts,
    val look: ThemeLook = ThemeLook.Flat,
) {
    /** Every corner of the interface is square. */
    val shapes get() = RoundedCornerShape(radius.dp).let { Shapes(extraSmall = it, small = it, medium = it, large = it, extraLarge = it) }
}

private fun c(hex: Long) = Color(hex or 0xFF000000)

/** The single dark, monochrome palette. Surfaces rise from the background toward the text colour. */
private fun studioScheme(): ColorScheme {
    val bg = c(0x111111); val fg = c(0xF5F5F5); val secondary = c(0xA6A6A6)
    fun tone(amount: Float) = lerp(bg, fg, amount)
    return darkColorScheme(
        primary = Color.White, onPrimary = Color.Black, primaryContainer = tone(.16f), onPrimaryContainer = fg,
        secondary = secondary, onSecondary = bg, secondaryContainer = tone(.12f), onSecondaryContainer = fg,
        tertiary = Color.White, onTertiary = Color.Black, tertiaryContainer = tone(.14f), onTertiaryContainer = fg,
        background = bg, onBackground = fg, surface = bg, onSurface = fg, surfaceVariant = tone(.09f), onSurfaceVariant = secondary,
        surfaceContainerLowest = bg, surfaceContainerLow = tone(.04f), surfaceContainer = tone(.06f),
        surfaceContainerHigh = tone(.1f), surfaceContainerHighest = tone(.14f), surfaceBright = tone(.16f), surfaceDim = bg,
        outline = tone(.42f), outlineVariant = tone(.16f), surfaceTint = Color.Transparent,
        inverseSurface = fg, inverseOnSurface = bg, inversePrimary = Color.Black, scrim = Color.Black,
        error = c(0xFF6B6B), onError = Color.Black, errorContainer = c(0x3A1414), onErrorContainer = c(0xFFB4AB),
    )
}

/** A palette in one hue: the surfaces rise from [bg] toward [fg], and [primary] is the accent of buttons and switches. */
private fun palette(bg: Color, fg: Color, secondary: Color, primary: Color, onPrimary: Color): ColorScheme {
    fun tone(amount: Float) = lerp(bg, fg, amount)
    return darkColorScheme(
        primary = primary, onPrimary = onPrimary, primaryContainer = tone(.16f), onPrimaryContainer = fg,
        secondary = secondary, onSecondary = bg, secondaryContainer = tone(.12f), onSecondaryContainer = fg,
        tertiary = primary, onTertiary = onPrimary, tertiaryContainer = tone(.14f), onTertiaryContainer = fg,
        background = bg, onBackground = fg, surface = bg, onSurface = fg, surfaceVariant = tone(.09f), onSurfaceVariant = secondary,
        surfaceContainerLowest = bg, surfaceContainerLow = tone(.04f), surfaceContainer = tone(.06f),
        surfaceContainerHigh = tone(.1f), surfaceContainerHighest = tone(.14f), surfaceBright = tone(.16f), surfaceDim = bg,
        outline = tone(.42f), outlineVariant = tone(.16f), surfaceTint = Color.Transparent,
        inverseSurface = fg, inverseOnSurface = bg, inversePrimary = onPrimary, scrim = Color.Black,
        error = c(0xFF6B6B), onError = Color.Black, errorContainer = c(0x3A1414), onErrorContainer = c(0xFFB4AB),
    )
}

/** Frosted panels: the surfaces are a little white over the coloured backdrop, and the ones that hold text for long are nearly opaque. */
private fun glassScheme(): ColorScheme {
    val fg = c(0xF2F6FF); val deep = c(0x131B2B)
    return palette(c(0x0B1220), fg, c(0xA9B8D4), c(0xBFE9FF), c(0x06202E)).copy(
        surfaceVariant = fg.copy(alpha = .09f), surfaceContainerLowest = Color.Transparent, surfaceContainerLow = fg.copy(alpha = .05f), surfaceContainer = fg.copy(alpha = .08f),
        surfaceContainerHigh = lerp(deep, fg, .08f), surfaceContainerHighest = lerp(deep, fg, .12f), surfaceBright = fg.copy(alpha = .16f),
        outline = fg.copy(alpha = .4f), outlineVariant = fg.copy(alpha = .14f), secondaryContainer = fg.copy(alpha = .12f), primaryContainer = fg.copy(alpha = .16f),
    )
}

/** Technical type and a green glow on black steel, like the front of a vintage amplifier. */
private val AnalogFonts by lazy { ThemeFonts(Mono, Mono, Mono, FontWeight.Medium, .6f, 0.sp) }

/** A serif title with wide spacing over a sans-serif body. */
private val GoldFonts by lazy { ThemeFonts(DmSans, FontFamily.Serif, Mono, FontWeight.Normal, .66f, 1.sp) }

/** The themes of the app. The first is the original, square and monochrome. */
val appThemes: List<AppTheme> = listOf(
    AppTheme("dark", R.string.theme_dark, R.string.theme_dark_text, true, studioScheme(), 0, StudioFonts),
    AppTheme("glass", R.string.theme_glass, R.string.theme_glass_text, true, glassScheme(), 20, StudioFonts, ThemeLook.Glass),
    AppTheme("analog", R.string.theme_analog, R.string.theme_analog_text, true, palette(c(0x0D0F0E), c(0xD9F7E0), c(0x86B08F), c(0x3DFF7A), Color.Black), 6, AnalogFonts, ThemeLook.Analog),
    AppTheme("gold", R.string.theme_gold, R.string.theme_gold_text, true, palette(c(0x0B0B0C), c(0xF3EBDD), c(0xB8A27A), c(0xD6B56A), c(0x1A1405)), 10, GoldFonts, ThemeLook.Gold),
)

/** Paints the backdrop of a theme behind the whole interface. */
fun DrawScope.drawBackdrop(look: ThemeLook, base: Color) {
    when (look) {
        ThemeLook.Flat -> drawRect(base)
        ThemeLook.Glass -> {
            drawRect(base)
            // Blobs of colour that the frosted panels sit on.
            fun blob(color: Long, x: Float, y: Float, radius: Float, alpha: Float) = drawCircle(Brush.radialGradient(listOf(c(color).copy(alpha = alpha), Color.Transparent), Offset(size.width * x, size.height * y), radius), radius, Offset(size.width * x, size.height * y))
            val r = size.maxDimension
            blob(0x19C7C0, .1f, .08f, r * .6f, .55f)
            blob(0x8B5CF6, .95f, .45f, r * .55f, .5f)
            blob(0x2563EB, .15f, .95f, r * .6f, .5f)
        }
        ThemeLook.Analog -> {
            drawRect(Brush.verticalGradient(listOf(c(0x1B1D1C), c(0x0B0C0B), c(0x141615))))
            // The brushed steel rails at both sides.
            val rail = 5.dp.toPx()
            val steel = Brush.horizontalGradient(listOf(c(0x5A5E5C), c(0xC9CDCB), c(0x6D716F)))
            drawRect(steel, Offset.Zero, Size(rail, size.height)); drawRect(steel, Offset(size.width - rail, 0f), Size(rail, size.height))
        }
        ThemeLook.Gold -> {
            drawRect(Brush.verticalGradient(listOf(c(0x0B0B0C), c(0x17120A))))
            drawCircle(Brush.radialGradient(listOf(c(0xD6B56A).copy(alpha = .22f), Color.Transparent), Offset(size.width * .85f, 0f), size.width), size.width, Offset(size.width * .85f, 0f))
        }
    }
}

/** The id that older versions used to follow the device. */
const val SYSTEM_THEME = "system"

/** The theme for an id, or the original one when the id is not known. */
@Suppress("UNUSED_PARAMETER")
fun themeFor(id: String, systemDark: Boolean): AppTheme = appThemes.firstOrNull { it.id == id } ?: appThemes.first()

/** Settings from older versions named a theme and a palette. Every one of them now maps to the single dark theme. */
@Suppress("UNUSED_PARAMETER")
fun migrateTheme(theme: String, palette: String?): String = "dark"
