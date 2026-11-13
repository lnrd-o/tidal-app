package io.github.yyan115.opentune.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DominantColorTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(c: Int) = c shr 16 and 0xFF
    private fun green(c: Int) = c shr 8 and 0xFF
    private fun blue(c: Int) = c and 0xFF

    @Test fun aMostlyBlueCoverGivesABrightBlue() {
        val pixels = IntArray(400) { if (it < 300) rgb(20, 40, 150) else rgb(200, 30, 30) }
        val c = DominantColor.of(pixels)
        assertNotNull(c)
        assertTrue("blue ${blue(c!!)} red ${red(c)}", blue(c) > 200 && red(c) < 120)
    }

    @Test fun darkAndGreyPixelsDoNotCount() {
        val pixels = IntArray(500) { if (it < 400) rgb(10, 10, 10) else if (it < 450) rgb(128, 128, 128) else rgb(220, 60, 20) }
        val c = DominantColor.of(pixels)
        // Only a tenth of the pixels have colour, and that is enough: they are the colour of the cover.
        assertNotNull(c)
        assertTrue(red(c!!) > 200 && green(c) < 140)
    }

    @Test fun aBlackAndWhiteCoverHasNoColour() {
        assertNull(DominantColor.of(IntArray(300) { if (it % 2 == 0) rgb(0, 0, 0) else rgb(255, 255, 255) }))
        assertNull(DominantColor.of(IntArray(300) { rgb(120, 120, 124) }))
        assertNull(DominantColor.of(IntArray(0)))
    }

    @Test fun aFewColouredPixelsInAGreyPictureAreNotItsColour() {
        val pixels = IntArray(1000) { if (it < 5) rgb(250, 0, 0) else rgb(110, 110, 110) }
        assertNull(DominantColor.of(pixels))
    }

    @Test fun theColourIsBrightEnoughForABlackScreen() {
        val c = DominantColor.of(IntArray(100) { rgb(60, 20, 90) })!!
        assertEquals(255, maxOf(red(c), green(c), blue(c)))
        assertEquals(0xFF, c ushr 24)
    }

    @Test fun neighbouringHuesAreOneColour() {
        // Orange and yellow-orange together beat a smaller patch of blue.
        val pixels = IntArray(300) { if (it < 100) rgb(230, 120, 20) else if (it < 200) rgb(230, 160, 20) else rgb(20, 20, 200) }
        val c = DominantColor.of(pixels)!!
        assertTrue("red ${red(c)} blue ${blue(c)}", red(c) > 200 && blue(c) < 100)
    }
}
