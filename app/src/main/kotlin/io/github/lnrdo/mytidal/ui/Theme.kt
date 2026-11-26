package io.github.lnrdo.mytidal.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The theme that is active, for the parts of the interface that depend on whether it is dark. */
val LocalAppTheme = staticCompositionLocalOf { appThemes.first { it.id == "dark" } }

/** True in the rounded design: round corners and blurred panes. */
val LocalRound = staticCompositionLocalOf { false }

/** The shape of a bottom sheet: square in the classic design, round at its top in the rounded one. */
val SheetShape: androidx.compose.ui.graphics.Shape
    @Composable get() = if (LocalRound.current) androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp) else androidx.compose.ui.graphics.RectangleShape

/**
 * Applies the theme of the app: light or [dark], and [rounded] or square. The colours are plain in both designs, black and white; only the player of the
 * rounded design takes its colours from the cover.
 */
@Composable
fun OpenTuneTheme(themeId: String = "dark", dark: Boolean = true, rounded: Boolean = false, effect: io.github.lnrdo.mytidal.core.effects.EffectOptions = io.github.lnrdo.mytidal.core.effects.EffectOptions(), content: @Composable () -> Unit) {
    val base = themeFor(themeId, true)
    val scheme = if (dark) base.scheme else studioLightScheme()
    val theme = AppTheme(base.id, base.nameRes, base.hintRes, dark, scheme, if (rounded) 18 else base.radius, base.fonts, base.look)
    CompositionLocalProvider(LocalAppTheme provides theme, LocalThemeFonts provides theme.fonts, LocalRound provides rounded, LocalImageEffect provides effect) {
        MaterialTheme(colorScheme = scheme, typography = themeTypography(theme.fonts), shapes = if (rounded) RoundShapes else theme.shapes) {
            val background = scheme.background
            // The whole app is drawn through the image effect, when one is chosen.
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().appEffect().drawBehind { drawBackdrop(theme.look, background) }) { content() }
        }
    }
}

/** Material's type scale in the typefaces of a theme. Headlines use the display face and everything else uses the body face. */
fun themeTypography(fonts: ThemeFonts): Typography {
    val base = Typography()
    val display = fonts.display
    val weight = fonts.displayWeight
    val tracking = fonts.displayTracking
    val body = fonts.body
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = display, fontWeight = weight, letterSpacing = tracking),
        displayMedium = base.displayMedium.copy(fontFamily = display, fontWeight = weight, letterSpacing = tracking),
        displaySmall = base.displaySmall.copy(fontFamily = display, fontWeight = weight, letterSpacing = tracking),
        headlineLarge = base.headlineLarge.copy(fontFamily = display, fontWeight = weight, letterSpacing = tracking),
        headlineMedium = base.headlineMedium.copy(fontFamily = display, fontWeight = weight, letterSpacing = tracking),
        headlineSmall = base.headlineSmall.copy(fontFamily = display, fontWeight = weight, letterSpacing = tracking),
        titleLarge = base.titleLarge.copy(fontFamily = display, fontWeight = weight, letterSpacing = tracking),
        titleMedium = base.titleMedium.copy(fontFamily = body, fontWeight = FontWeight.Medium), titleSmall = base.titleSmall.copy(fontFamily = body, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontFamily = body), bodyMedium = base.bodyMedium.copy(fontFamily = body),
        bodySmall = base.bodySmall.copy(fontFamily = body), labelLarge = base.labelLarge.copy(fontFamily = body),
        labelMedium = base.labelMedium.copy(fontFamily = body), labelSmall = base.labelSmall.copy(fontFamily = body),
    )
}
