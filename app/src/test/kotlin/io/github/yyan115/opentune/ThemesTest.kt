package io.github.yyan115.opentune

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import io.github.yyan115.opentune.ui.SYSTEM_THEME
import io.github.yyan115.opentune.ui.appThemes
import io.github.yyan115.opentune.ui.migrateTheme
import io.github.yyan115.opentune.ui.themeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemesTest {
    /** The contrast ratio of two colours as WCAG defines it. Body text needs 4.5 and large text or icons need 3. */
    private fun contrast(a: Color, b: Color): Double {
        val high = maxOf(a.luminance(), b.luminance()).toDouble(); val low = minOf(a.luminance(), b.luminance()).toDouble()
        return (high + .05) / (low + .05)
    }

    @Test fun theThemeHasReadableText() {
        val failures = mutableListOf<String>()
        for (theme in appThemes) {
            val scheme = theme.scheme
            fun check(name: String, foreground: Color, background: Color, minimum: Double) {
                val ratio = contrast(foreground, background)
                if (ratio < minimum) failures += "${theme.id}: $name is %.2f, below %.1f".format(ratio, minimum)
            }
            check("text on the background", scheme.onBackground, scheme.background, 7.0)
            check("secondary text on the background", scheme.onSurfaceVariant, scheme.background, 4.5)
            check("secondary text on a raised surface", scheme.onSurfaceVariant, scheme.surfaceContainerHigh, 4.5)
            check("text on a raised surface", scheme.onSurface, scheme.surfaceContainerHighest, 7.0)
            check("accent on the background", scheme.primary, scheme.background, 4.5)
            check("text on a button", scheme.onPrimary, scheme.primary, 4.5)
            check("borders on the background", scheme.outline, scheme.background, 3.0)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun thereIsOneDarkSquareTheme() {
        assertEquals(listOf("dark"), appThemes.map { it.id })
        assertTrue(appThemes.single().dark)
        assertTrue(appThemes.single().scheme.background.luminance() < .1f)
        assertEquals(0, appThemes.single().radius)
    }

    @Test fun everyIdGivesTheDarkTheme() {
        assertEquals("dark", themeFor(SYSTEM_THEME, systemDark = false).id)
        assertEquals("dark", themeFor("light", systemDark = true).id)
        assertEquals("dark", themeFor("missing", systemDark = false).id)
        assertEquals("dark", migrateTheme("light", "paper"))
        assertEquals("dark", migrateTheme("cyberpunk", null))
    }
}
