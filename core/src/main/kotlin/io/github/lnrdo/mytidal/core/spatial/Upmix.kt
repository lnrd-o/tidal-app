package io.github.lnrdo.mytidal.core.spatial

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The channels of a surround stream, in the order that Android interleaves them (5.1 uses the first six, 7.1 all eight). */
enum class SpatialChannel(val short: String) {
    FRONT_LEFT("L"), FRONT_RIGHT("R"), CENTER("C"), LFE("LFE"), BACK_LEFT("BL"), BACK_RIGHT("BR"), SIDE_LEFT("SL"), SIDE_RIGHT("SR")
}

/**
 * How a stereo song is spread over the speakers that a spatializer places around the listener. [center] is the level of what both channels share (the voice,
 * the kick), of which [take] is taken out of the front pair; [front] is the level of the front pair; [side] and [rear] are the levels of the ambience (what
 * the two channels do not share) at the sides and behind; [lfe] is the level of the bass in the low-frequency channel; [depth] scales the delays that
 * set the ambience apart from the front, which is what makes it sound behind and not on top of it.
 */
enum class SpatialMode(val id: String, val center: Float, val take: Float, val front: Float, val side: Float, val rear: Float, val lfe: Float, val depth: Float) {
    /** A concert hall: the voice in front, the room around it. */
    STAGE("stage", .85f, .55f, 1f, .25f, .30f, .50f, 1f),
    /** A wide field: the band in front, the ambience all around. */
    SURROUND("surround", .60f, .40f, .90f, .50f, .60f, .55f, 1.2f),
    /** Inside the music: little in front, the ambience behind and at the sides. */
    IMMERSIVE("immersive", .45f, .50f, .70f, .85f, .85f, .60f, 1.5f),
    /** The voice anchored in the centre, with the least around it. */
    FOCUS("focus", 1f, .80f, .85f, .10f, .15f, .45f, .8f);

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } }
}

/**
 * A live upmix of stereo to 5.1 or 7.1, sample by sample and with no look-ahead. What the two channels share, weighted by how alike they are over the last
 * 40 ms (their coherence), goes to the centre; what they do not share (the ambience: reverb, wide instruments) goes to the sides and the back, set apart by
 * short delays and a darker tone; the bass goes to the low-frequency channel. Silence, and a signal that is not stereo, stay as they are.
 * The levels of the channels are kept in [levels] for a meter.
 */
class Upmixer(private val rate: Int, val channels: Int = 6) {
    init { require(channels == 6 || channels == 8) }

    @Volatile var mode: SpatialMode = SpatialMode.STAGE

    /** The level of each channel (RMS of the last block, with a short hold), in [SpatialChannel] order; the unused ones stay 0. */
    val levels = FloatArray(8)

    private val smooth = exp(-1.0 / (.04 * rate)).toFloat()
    private var ll = 0f; private var rr = 0f; private var lr = 0f
    private val size = (rate * .06).toInt() + 1
    private val delayL = FloatArray(size); private val delayR = FloatArray(size)
    private var write = 0
    // One-pole low-pass of the ambience at about 6 kHz: what is far away is darker.
    private val tone = (1.0 - exp(-2 * PI * 6000 / rate)).toFloat()
    private var darkL = 0f; private var darkR = 0f
    private val bass = Biquad.lowPass(rate, 110.0)
    private val gain = .8f

    fun reset() { ll = 0f; rr = 0f; lr = 0f; delayL.fill(0f); delayR.fill(0f); darkL = 0f; darkR = 0f; bass.reset(); levels.fill(0f) }

    /** Upmixes [frames] frames of interleaved stereo from [input] to [output], interleaved with [channels] channels. */
    fun process(input: FloatArray, inputOffset: Int, frames: Int, output: FloatArray, outputOffset: Int) {
        val m = mode
        val ambience = ambienceDelays(m)
        sum.fill(0f)
        for (n in 0 until frames) {
            val l = input[inputOffset + 2 * n]; val r = input[inputOffset + 2 * n + 1]
            ll = smooth * ll + (1 - smooth) * l * l; rr = smooth * rr + (1 - smooth) * r * r; lr = smooth * lr + (1 - smooth) * l * r
            val coherence = if (ll + rr < 1e-9f) 0f else (2 * lr / (ll + rr)).coerceIn(0f, 1f)
            val shared = coherence * coherence
            val mid = (l + r) * .5f
            val common = mid * shared
            // What is left of each channel once the shared part is taken out: the ambience.
            val ambientL = l - common; val ambientR = r - common
            delayL[write] = ambientL; delayR[write] = ambientR
            val sideL = tap(delayL, ambience[0]); val sideR = tap(delayR, ambience[1])
            val backL = tap(delayL, ambience[2]) - .3f * tap(delayR, ambience[2])
            val backR = tap(delayR, ambience[3]) - .3f * tap(delayL, ambience[3])
            write = if (write + 1 == size) 0 else write + 1
            darkL += tone * ((backL * m.rear) - darkL); darkR += tone * ((backR * m.rear) - darkR)
            val low = bass.process(mid) * m.lfe
            val fl = (l - common * m.take) * m.front * gain
            val fr = (r - common * m.take) * m.front * gain
            val c = common * m.center * gain * 1.4f
            val sl = sideL * m.side * gain; val sr = sideR * m.side * gain
            val bl = darkL * gain; val br = darkR * gain
            val o = outputOffset + n * channels
            output[o] = fl; output[o + 1] = fr; output[o + 2] = c; output[o + 3] = low * gain
            if (channels == 8) { output[o + 4] = bl; output[o + 5] = br; output[o + 6] = sl; output[o + 7] = sr }
            else { output[o + 4] = bl + sl * .7f; output[o + 5] = br + sr * .7f }
            for (k in 0 until channels) sum[k] += output[o + k] * output[o + k]
        }
        // The meter: the level of the block, with a quick rise and a slow fall.
        if (frames > 0) for (k in 0 until channels) {
            val level = sqrt(sum[k] / frames)
            levels[k] = max(level, levels[k] * .85f)
        }
    }

    private val delays = FloatArray(4)
    private val sum = FloatArray(8)
    /** The delays of the ambience, in samples: left side, right side, left back, right back. They differ so that the channels do not repeat each other. */
    private fun ambienceDelays(m: SpatialMode): FloatArray {
        val ms = floatArrayOf(8f, 11f, 19f, 23f)
        for (i in 0 until 4) delays[i] = min(size - 2f, ms[i] * m.depth * rate / 1000f)
        return delays
    }

    /** The sample [back] samples before the one just written, read between two samples. */
    private fun tap(line: FloatArray, back: Float): Float {
        val whole = back.toInt(); val part = back - whole
        val a = line[(write - whole + size) % size]; val b = line[(write - whole - 1 + size) % size]
        return a + (b - a) * part
    }
}

/** A second-order low-pass filter (Butterworth). */
class Biquad private constructor(private val b0: Float, private val b1: Float, private val b2: Float, private val a1: Float, private val a2: Float) {
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f
    fun reset() { x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f }
    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    companion object {
        fun lowPass(rate: Int, cutoff: Double): Biquad {
            val w = 2 * PI * cutoff / rate
            val alpha = sin(w) / (2 * (1 / sqrt(2.0)))
            val a0 = 1 + alpha
            val b = (1 - cos(w)) / 2
            return Biquad((b / a0).toFloat(), ((1 - cos(w)) / a0).toFloat(), (b / a0).toFloat(), ((-2 * cos(w)) / a0).toFloat(), ((1 - alpha) / a0).toFloat())
        }
    }
}

/** Reads the levels of an [Upmixer] as numbers from 0 to 1 for a display: a logarithmic scale from -50 dB to 0. */
fun meterLevel(level: Float): Float = if (level <= 1e-5f) 0f else ((20f * kotlin.math.log10(level) + 50f) / 50f).coerceIn(0f, 1f)

