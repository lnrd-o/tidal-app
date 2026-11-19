package io.github.lnrdo.mytidal.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PeakWindowTest {
    private fun run(w: PeakWindow, level: Float, seconds: Float) {
        val levels = floatArrayOf(level, level)
        var t = 0f
        while (t < seconds) { w.add(levels, .05f); t += .05f }
    }

    @Test fun aPeakStaysForTheWholeWindowAndThenLeaves() {
        val w = PeakWindow(2)
        run(w, .9f, .1f)
        run(w, .2f, 2.5f)
        assertEquals(.9f, w.highest[0], 0f)
        run(w, .2f, 1.0f)
        assertEquals(.2f, w.highest[0], 0f)
    }

    @Test fun eachColumnKeepsItsOwnHighest() {
        val w = PeakWindow(2)
        w.add(floatArrayOf(.3f, .8f), .05f); w.add(floatArrayOf(.6f, .1f), .05f)
        assertEquals(.6f, w.highest[0], 0f); assertEquals(.8f, w.highest[1], 0f)
    }

    @Test fun aHigherLevelReplacesTheHeldOne() {
        val w = PeakWindow(2)
        run(w, .4f, .5f); run(w, .7f, .1f)
        assertEquals(.7f, w.highest[1], 0f)
    }
}
