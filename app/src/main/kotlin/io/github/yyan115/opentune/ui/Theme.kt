package io.github.yyan115.opentune.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontWeight

/** The theme that is active, for the parts of the interface that depend on whether it is dark. */
val LocalAppTheme = staticCompositionLocalOf { appThemes.first { it.id == "dark" } }

/** Applies the one theme of the app. */
@Composable
fun OpenTuneTheme(content: @Composable () -> Unit) {
    val theme = appThemes.first()
    CompositionLocalProvider(LocalAppTheme provides theme, LocalThemeFonts provides theme.fonts) {
        MaterialTheme(colorScheme = theme.scheme, typography = themeTypography(theme.fonts), shapes = theme.shapes, content = content)
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
