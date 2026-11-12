package io.github.yyan115.opentune.core.dj

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A second-order Butterworth section in transposed direct form II. Two of them in a row give a Linkwitz-Riley filter of the fourth order. */
class Biquad(private val highpass: Boolean, rate: Int, frequency: Double) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var z1 = 0.0
    private var z2 = 0.0

    init {
        val w = 2.0 * PI * frequency / rate
        val alpha = sin(w) / (2.0 * Q)
        val c = cos(w)
        val a0 = 1.0 + alpha
        if (highpass) { b0 = (1 + c) / 2 / a0; b1 = -(1 + c) / a0; b2 = (1 + c) / 2 / a0 }
        else { b0 = (1 - c) / 2 / a0; b1 = (1 - c) / a0; b2 = (1 - c) / 2 / a0 }
        a1 = -2.0 * c / a0
        a2 = (1.0 - alpha) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0.0; z2 = 0.0 }

    private companion object { const val Q = 0.70710678118 }
}

/** Splits one channel into low, mid and high bands with Linkwitz-Riley crossovers and sums them with a gain each. */
class BandSplit(rate: Int, lowHz: Double, highHz: Double) {
    private val lowA = Biquad(false, rate, lowHz); private val lowB = Biquad(false, rate, lowHz)
    private val restA = Biquad(true, rate, lowHz); private val restB = Biquad(true, rate, lowHz)
    private val midA = Biquad(false, rate, highHz); private val midB = Biquad(false, rate, highHz)
    private val highA = Biquad(true, rate, highHz); private val highB = Biquad(true, rate, highHz)

    fun process(x: Double, gainLow: Double, gainMid: Double, gainHigh: Double): Double {
        val low = lowB.process(lowA.process(x))
        val rest = restB.process(restA.process(x))
        val mid = midB.process(midA.process(rest))
        val high = highB.process(highA.process(rest))
        return gainLow * low + gainMid * mid + gainHigh * high
    }

    fun reset() { listOf(lowA, lowB, restA, restB, midA, midB, highA, highB).forEach(Biquad::reset) }
}

/** A stereo three-band equalizer: the DJ's low, mid and high knobs, as gains between 0 (killed) and 1. */
class ThreeBandEq(rate: Int, lowHz: Double = 250.0, highHz: Double = 2500.0) {
    private val left = BandSplit(rate, lowHz, highHz)
    private val right = BandSplit(rate, lowHz, highHz)

    fun processLeft(x: Float, low: Double, mid: Double, high: Double): Float = left.process(x.toDouble(), low, mid, high).toFloat()
    fun processRight(x: Float, low: Double, mid: Double, high: Double): Float = right.process(x.toDouble(), low, mid, high).toFloat()

    fun reset() { left.reset(); right.reset() }
}

/**
 * A stereo resonant low-pass or high-pass filter whose cutoff can move while the music plays: the filter sweep of a DJ mixer.
 * It is a zero-delay state-variable filter, which stays stable and smooth when its cutoff changes at every block.
 * It passes the signal unchanged, and keeps no state, while the cutoff is out of the audible sweep range.
 */
class SweepFilter(private val rate: Int, private val highpass: Boolean, private val resonance: Double = 0.9) {
    private val ic1 = DoubleArray(2)
    private val ic2 = DoubleArray(2)
    private var a1 = 0.0
    private var a2 = 0.0
    private var a3 = 0.0
    private var k = 1.0 / resonance
    /** True while the filter changes the sound. */
    var active = false
        private set

    /** Sets the cutoff in hertz. A low-pass at or above [OPEN_HZ] and a high-pass at or below [CLOSED_HZ] do nothing. */
    fun setCutoff(hz: Double) {
        val bypass = if (highpass) hz <= CLOSED_HZ else hz >= OPEN_HZ
        if (bypass) { active = false; ic1.fill(0.0); ic2.fill(0.0); return }
        active = true
        val g = Math.tan(PI * hz.coerceIn(10.0, rate * 0.45) / rate)
        a1 = 1.0 / (1.0 + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    fun process(channel: Int, x: Float): Float {
        if (!active) return x
        val v3 = x - ic2[channel]
        val v1 = a1 * ic1[channel] + a2 * v3
        val v2 = ic2[channel] + a2 * ic1[channel] + a3 * v3
        ic1[channel] = 2 * v1 - ic1[channel]
        ic2[channel] = 2 * v2 - ic2[channel]
        return if (highpass) (x - k * v1 - v2).toFloat() else v2.toFloat()
    }

    fun reset() { ic1.fill(0.0); ic2.fill(0.0); active = false }

    companion object {
        const val OPEN_HZ = 17_000.0
        const val CLOSED_HZ = 30.0
    }
}
