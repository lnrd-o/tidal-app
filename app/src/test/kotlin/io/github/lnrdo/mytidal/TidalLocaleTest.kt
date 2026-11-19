package io.github.lnrdo.mytidal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class TidalLocaleTest {
    @Test fun joinsLanguageAndRegionTheWayTidalExpectsIt() {
        assertEquals("de_DE", tidalLocale(Locale.GERMANY))
        assertEquals("en_GB", tidalLocale(Locale.forLanguageTag("en-GB")))
        assertEquals("pt_BR", tidalLocale(Locale.forLanguageTag("pt-BR")))
    }

    @Test fun fillsTheRegionWhenTheDeviceLeavesItOut() {
        assertEquals("fr_US", tidalLocale(Locale.FRENCH))
        assertEquals("en_US", tidalLocale(Locale.ROOT))
    }
}
