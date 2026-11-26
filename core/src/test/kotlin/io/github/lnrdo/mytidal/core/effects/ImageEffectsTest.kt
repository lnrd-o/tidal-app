package io.github.lnrdo.mytidal.core.effects

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ImageEffectsTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private fun grey(level: Int) = (0xFF shl 24) or (level shl 16) or (level shl 8) or level

    @Test fun theBayerThresholdIsTheMatrixPlusAHalfOverSixteen() {
        for (y in 0 until 4) for (x in 0 until 4) assertEquals((ImageEffects.BAYER[y][x] + .5f) / 16f, ImageEffects.bayerThreshold(x, y, 4), 1e-6f)
        // The matrix repeats every four grains.
        assertEquals(ImageEffects.bayerThreshold(1, 2, 4), ImageEffects.bayerThreshold(5, 6, 4), 0f)
    }

    @Test fun theBayerGrainGrowsWithTheCell() {
        // A cell of 8 pixels makes grains of 2: pixels 0 and 1 share a threshold, pixel 2 takes the next one.
        assertEquals(ImageEffects.bayerThreshold(0, 0, 8), ImageEffects.bayerThreshold(1, 1, 8), 0f)
        assertEquals((8 + .5f) / 16f, ImageEffects.bayerThreshold(2, 0, 8), 1e-6f)
    }

    @Test fun theShaderFormulaOfTheBayerMatrixGivesTheMatrix() {
        for (y in 0 until 4) for (x in 0 until 4) assertEquals(ImageEffects.BAYER[y][x], ImageEffects.bayerFormula(x, y))
    }

    @Test fun ditherLightsAPixelOnlyAboveItsThreshold() {
        // The lowest threshold is 0.5/16 and the highest 15.5/16.
        assertFalse(ImageEffects.ditherOn(.5f / 16f, 0, 0, 4))
        assertTrue(ImageEffects.ditherOn(.5f / 16f + .001f, 0, 0, 4))
        assertFalse(ImageEffects.ditherOn(.9f, 0, 3, 4)) // threshold 15.5/16 ≈ 0.97
    }

    @Test fun ditherDrawsTwoColoursAndHalfGreyLightsHalfThePixels() {
        val w = 16; val h = 16
        val o = EffectOptions(ImageStyle.DITHER, cellSize = 4, contrast = 1f, shadow = black, light = white)
        val out = ImageEffects.render(IntArray(w * h) { grey(128) }, w, h, o)
        assertEquals(setOf(black, white), out.toSet())
        assertEquals(w * h / 2, out.count { it == white })
    }

    @Test fun theHalftoneRadiusFollowsTheSquareRootOfTheBrightness() {
        assertEquals(0f, ImageEffects.halftoneRadius(0f, 10f), 0f)
        assertEquals(7.2f, ImageEffects.halftoneRadius(1f, 10f), 1e-5f)
        assertEquals(7.2f * sqrt(.25f), ImageEffects.halftoneRadius(.25f, 10f), 1e-5f)
        // The area of the dot, so the share of light, grows with the brightness.
        val a1 = ImageEffects.halftoneRadius(.2f, 10f).let { it * it }
        val a2 = ImageEffects.halftoneRadius(.4f, 10f).let { it * it }
        assertEquals(2f, a2 / a1, 1e-4f)
        // Above half the cell, bright dots touch their neighbours.
        assertTrue(ImageEffects.halftoneRadius(1f, 10f) > 5f)
    }

    @Test fun halftoneShowsBrightnessOnlyByTheSizeOfHardDots() {
        val w = 40; val h = 40
        val o = EffectOptions(ImageStyle.HALFTONE, cellSize = 10, contrast = 1f, shadow = black, light = white)
        val dark = ImageEffects.render(IntArray(w * h) { grey(60) }, w, h, o)
        val bright = ImageEffects.render(IntArray(w * h) { grey(200) }, w, h, o)
        assertEquals(setOf(black, white), (dark + bright).toSet())
        assertTrue(bright.count { it == white } > dark.count { it == white })
    }

    @Test fun theSameImageAndOptionsAlwaysGiveTheSameResult() {
        val w = 33; val h = 21
        val image = IntArray(w * h) { i -> grey((i * 37) % 256) }
        for (style in ImageStyle.entries) {
            val o = EffectOptions(style, cellSize = 5, gridAngle = 30)
            assertArrayEquals(ImageEffects.render(image, w, h, o), ImageEffects.render(image, w, h, o))
        }
    }

    @Test fun thePixelMosaicLeavesADarkGridBetweenCells() {
        val w = 20; val h = 20
        val o = EffectOptions(ImageStyle.PIXEL, cellSize = 10, shape = DotShape.SQUARE, shadow = grey(100), light = white)
        val out = ImageEffects.render(IntArray(w * h) { white }, w, h, o)
        assertEquals(white, out[5 * w + 5]) // the middle of a cell
        assertTrue(out[0] != white) // its corner is the grid
    }

    @Test fun theContrastOfTwoColoursFollowsWcag() {
        assertEquals(21.0, ImageEffects.contrastRatio(black, white), .01)
        assertEquals(1.0, ImageEffects.contrastRatio(white, white), .001)
        val fixed = ImageEffects.readable(grey(120), grey(150))
        assertTrue(ImageEffects.contrastRatio(fixed.first, fixed.second) >= 4.5)
    }
}
