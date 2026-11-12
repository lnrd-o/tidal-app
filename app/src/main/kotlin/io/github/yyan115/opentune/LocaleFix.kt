package io.github.yyan115.opentune

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Some phones that run Android apps through a compatibility layer (HarmonyOS, for one) report a configuration without any locale. The system
 * then fails inside its own number formatting and plural rules ("Unable to construct NumberFormat", a null ULocale), which crashes any screen that
 * shows a file size or a counted noun. When the locale is missing, this gives the context one: the default of the Java runtime, or English.
 */
object LocaleFix {
    fun wrap(base: Context): Context {
        val locales = base.resources.configuration.locales
        if (locales.size() > 0 && locales.get(0) != null) return base
        val fixed = Configuration(base.resources.configuration)
        fixed.setLocale(Locale.getDefault()?.takeIf { it.language.isNotEmpty() } ?: Locale.US)
        return base.createConfigurationContext(fixed)
    }
}
