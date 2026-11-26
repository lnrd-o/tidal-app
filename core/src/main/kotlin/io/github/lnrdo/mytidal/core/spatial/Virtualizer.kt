package io.github.lnrdo.mytidal.core.spatial

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Plays the channels of an [Upmixer] (5.1 or 7.1) on two ears or two speakers, for a device whose system does not spatialize surround sound itself.
 *
 * Headphones: each channel is a virtual speaker at its angle around the head. The ear on the far side hears it later (the time that sound takes around the
 * head, up to about 0.65 ms), quieter and darker (the head's shadow); a speaker behind is a little darker for both ears, as the outer ear shades sound from
 * behind. The low-frequency channel goes to both ears alike.
 *
 * Speakers: the same, and then what each speaker sends to the opposite ear is partly cancelled (crosstalk cancellation), above the bass only, so that the
 * sides and the back reach past the edges of a phone or a laptop.
 */
class Virtualizer(private val rate: Int, private val channels: Int, private val speakers: Boolean) {
    init { require(channels == 6 || channels == 8) }

    private class Source(val azimuth: Double, val lfe: Boolean = false)

    /** The angle of each channel from the front, clockwise, in degrees: ITU positions (5.1 surrounds at 110 degrees, 7.1 sides at 90 and backs at 150). */
    private val sources = Array(channels) { k ->
        when (SpatialChannel.entries[k]) {
            SpatialChannel.FRONT_LEFT -> Source(-30.0); SpatialChannel.FRONT_RIGHT -> Source(30.0); SpatialChannel.CENTER -> Source(0.0)
            SpatialChannel.LFE -> Source(0.0, lfe = true)
            SpatialChannel.BACK_LEFT -> Source(if (channels == 6) -110.0 else -150.0); SpatialChannel.BACK_RIGHT -> Source(if (channels == 6) 110.0 else 150.0)
            SpatialChannel.SIDE_LEFT -> Source(-90.0); SpatialChannel.SIDE_RIGHT -> Source(90.0)
        }
    }

    /** For each channel: the gain of the near and the far ear, the delay of the far ear in samples, the head-shadow filter of the far ear and the rear tone. */
    private val near = DoubleArray(channels); private val far = DoubleArray(channels); private val delay = IntArray(channels)
    private val shadow = DoubleArray(channels); private val rear = DoubleArray(channels)
    private val shadowState = DoubleArray(channels); private val rearState = DoubleArray(channels)
    private val size = 64
    private val lines = Array(channels) { DoubleArray(size) }
    private var write = 0
    private val rearTone = 1 - exp(-2 * PI * 5000.0 / rate)

    init {
        for (k in 0 until channels) {
            val s = sources[k]
            if (s.lfe) { near[k] = .7; far[k] = .7; continue }
            val theta = Math.toRadians(abs(s.azimuth))
            // The angle that sets the time around the head: a source behind is as far round as its mirror in front.
            val side = if (theta > PI / 2) PI - theta else theta
            val itd = .0875 / 343.0 * (side + sin(side))
            delay[k] = (itd * rate).toInt().coerceIn(0, size - 1)
            val lateral = sin(side)
            near[k] = 1 + .25 * lateral
            far[k] = 1 - .35 * lateral
            // The head shadow: from about 20 kHz in front down to about 1.6 kHz at the side.
            val cutoff = 1600.0 + (1 - lateral) * 18000.0
            shadow[k] = 1 - exp(-2 * PI * cutoff / rate)
            rear[k] = if (theta > PI / 2) .45 * (theta - PI / 2) / (PI / 2) + .15 else 0.0
        }
    }

    // Crosstalk cancellation for speakers: above about 250 Hz, each output takes away part of the other, a little later and darker.
    // One sample: the speakers of a phone or a laptop are a few centimetres apart, and a longer delay turns the cancellation into a comb filter in its band.
    private val ctcDelay = 1
    private val ctcGain = .45
    private val ctcLine = Array(2) { DoubleArray(16) }
    private var ctcWrite = 0
    private val split = 1 - exp(-2 * PI * 250.0 / rate)
    private val ctcTone = 1 - exp(-2 * PI * 6000.0 / rate)
    private val lowState = DoubleArray(2); private val toneState = DoubleArray(2)
    private val gain = .62

    fun reset() {
        lines.forEach { it.fill(0.0) }; shadowState.fill(0.0); rearState.fill(0.0); write = 0
        ctcLine.forEach { it.fill(0.0) }; lowState.fill(0.0); toneState.fill(0.0); ctcWrite = 0
    }

    /** Turns [frames] frames of [channels] interleaved channels in [input] into interleaved stereo in [output]. */
    fun process(input: FloatArray, inputOffset: Int, frames: Int, output: FloatArray, outputOffset: Int) {
        for (n in 0 until frames) {
            var left = 0.0; var right = 0.0
            val base = inputOffset + n * channels
            for (k in 0 until channels) {
                var x = input[base + k].toDouble()
                val s = sources[k]
                if (s.lfe) { left += x * near[k]; right += x * near[k]; continue }
                if (rear[k] > 0) { rearState[k] += rearTone * (x - rearState[k]); x = x * (1 - rear[k]) + rearState[k] * rear[k] }
                val line = lines[k]
                line[write] = x
                val late = line[(write - delay[k] + size) % size]
                shadowState[k] += shadow[k] * (late - shadowState[k])
                val farEar = shadowState[k] * far[k]
                val nearEar = x * near[k]
                when {
                    s.azimuth < 0 -> { left += nearEar; right += farEar }
                    s.azimuth > 0 -> { left += farEar; right += nearEar }
                    else -> { left += x; right += x }
                }
            }
            write = (write + 1) % size
            left *= gain; right *= gain
            if (speakers) {
                val previousL = ctcLine[0][(ctcWrite - ctcDelay + 16) % 16]; val previousR = ctcLine[1][(ctcWrite - ctcDelay + 16) % 16]
                toneState[0] += ctcTone * (previousR - toneState[0]); toneState[1] += ctcTone * (previousL - toneState[1])
                lowState[0] += split * (left - lowState[0]); lowState[1] += split * (right - lowState[1])
                // The bass passes untouched; above it, each side loses what the other speaker sends to its ear.
                val outL = lowState[0] + (left - lowState[0]) - ctcGain * (toneState[0] - lowPassOf(0))
                val outR = lowState[1] + (right - lowState[1]) - ctcGain * (toneState[1] - lowPassOf(1))
                ctcLine[0][ctcWrite] = outL; ctcLine[1][ctcWrite] = outR
                ctcWrite = (ctcWrite + 1) % 16
                left = outL; right = outR
            }
            output[outputOffset + 2 * n] = left.toFloat(); output[outputOffset + 2 * n + 1] = right.toFloat()
        }
    }

    // The bass of the cancelled signal, which the cancellation leaves alone.
    private val cancelLow = DoubleArray(2)
    private fun lowPassOf(side: Int): Double { cancelLow[side] += split * (toneState[side] - cancelLow[side]); return cancelLow[side] }
}
