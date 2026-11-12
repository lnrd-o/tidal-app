package io.github.yyan115.opentune.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
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

/** One complete look: colours, corner radius and typefaces. */
class AppTheme(val id: String, @StringRes val nameRes: Int, val dark: Boolean, val scheme: ColorScheme, val radius: Int, val fonts: ThemeFonts) {
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

/** The app has one look: dark, monochrome and square. The list stays so that settings and tools can name it. */
val appThemes: List<AppTheme> = listOf(AppTheme("dark", R.string.theme_dark, true, studioScheme(), 0, StudioFonts))

/** The id that older versions used to follow the device. */
const val SYSTEM_THEME = "system"

/** The theme for an id. There is one theme, so every id gives it. */
@Suppress("UNUSED_PARAMETER")
fun themeFor(id: String, systemDark: Boolean): AppTheme = appThemes.first()

/** Settings from older versions named a theme and a palette. Every one of them now maps to the single dark theme. */
@Suppress("UNUSED_PARAMETER")
fun migrateTheme(theme: String, palette: String?): String = "dark"
