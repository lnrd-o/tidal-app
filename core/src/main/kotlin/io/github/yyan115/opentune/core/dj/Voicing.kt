package io.github.yyan115.opentune.core.dj

import kotlin.math.PI
import kotlin.math.exp

/** The voices of the studio bus. ORIGINAL leaves the bus as it is, and the others add a space around the sound or open the stereo image. */
enum class Voice {
    /** The studio bus as it is. */
    ORIGINAL,
    /** A deep hall behind the music. */
    SPACE,
    /** The early reflections and the short tail of a room. */
    AMBIENCE,
    /** The stereo image opens to the sides, and the lows stay in the centre. */
    WIDE
}

/**
 * The stage behind the voices of the studio bus. Three amounts from 0 to 1 are read as a different thing by each voice:
 *
 * - **SPACE**: how much hall, how big it is, how bright it is.
 * - **AMBIENCE**: how loud the room is, how big it is, how far away it sounds.
 * - **WIDE**: how wide the image is, how high the lows that stay in the centre go, how much depth is added by a short delayed copy of the centre.
 *
 * The room is a small tail of eight comb filters and four all-pass filters, one set for each channel, with a different length on the right so that
 * the two ears hear two rooms. The widening takes the sides apart from the centre.
 */
class Voicing(private val rate: Int) {
    var voice = Voice.ORIGINAL; private set
    private var a = 0f; private var b = 0f; private var c = 0f

    /** True when the stage changes the sound. */
    val engaged: Boolean get() = voice != Voice.ORIGINAL && a > 0f

    var outLeft = 0f; private set
    var outRight = 0f; private set

    private val scale = rate / 44_100.0
    private val combBase = intArrayOf(1116, 1188, 1277, 1356)
    private val allpassBase = intArrayOf(556, 441)
    private class Comb(size: Int) {
        val buffer = FloatArray(size); var length = size; var index = 0; var held = 0f
        fun run(x: Float, feedback: Float, damp: Float): Float {
            val y = buffer[index]
            held = y * (1f - damp) + held * damp
            buffer[index] = x + held * feedback
            if (++index >= length) index = 0
            return y
        }
        fun clear() { buffer.fill(0f); index = 0; held = 0f }
    }
    private class AllPass(size: Int) {
        val buffer = FloatArray(size); var index = 0
        fun run(x: Float): Float {
            val y = buffer[index]
            buffer[index] = x + y * 0.5f
            if (++index >= buffer.size) index = 0
            return y - x
        }
    }
    private val combsLeft = Array(combBase.size) { Comb((combBase[it] * scale * 1.0).toInt() + 8) }
    private val combsRight = Array(combBase.size) { Comb(((combBase[it] + 23) * scale * 1.0).toInt() + 8) }
    private val allLeft = Array(allpassBase.size) { AllPass((allpassBase[it] * scale).toInt() + 1) }
    private val allRight = Array(allpassBase.size) { AllPass(((allpassBase[it] + 23) * scale).toInt() + 1) }

    // The delay that feeds the room, and the taps of the early reflections.
    private val delayLength = (rate * 0.14).toInt()
    private val delay = FloatArray(delayLength)
    private var write = 0
    private val tapsLeftMs = doubleArrayOf(7.0, 13.0, 21.0, 31.0)
    private val tapsRightMs = doubleArrayOf(9.0, 17.0, 26.0, 37.0)
    private val tapGain = floatArrayOf(.5f, .4f, .3f, .22f)

    private var feedback = .8f; private var damp = .3f; private var wet = 0f; private var early = 0f; private var predelay = 0; private var lengthScale = 1.0
    // The widening.
    private var width = 1f; private var depth = 0f
    private var sideLow = 0.0; private var lowCoefficient = 0.0
    private val haas = FloatArray((rate * 0.02).toInt() + 1); private var haasWrite = 0
    private var haasFilter = 0.0

    fun configure(voice: Voice, a: Float, b: Float, c: Float) {
        if (voice != this.voice) clear()
        this.voice = voice; this.a = a.coerceIn(0f, 1f); this.b = b.coerceIn(0f, 1f); this.c = c.coerceIn(0f, 1f)
        when (voice) {
            Voice.SPACE -> { wet = this.a * .55f; feedback = .80f + .17f * this.b; damp = .55f * (1f - this.c) + .08f; early = 0f; predelay = (rate * .025).toInt(); setLength(1.0) }
            Voice.AMBIENCE -> { wet = this.a * .35f; feedback = .55f + .2f * this.b; damp = .3f; early = this.a * .8f; predelay = (rate * this.c * .03).toInt(); setLength(.35 + .35 * this.b) }
            Voice.WIDE -> {
                width = 1f + 1.4f * this.a; depth = this.c * .45f
                lowCoefficient = 1 - exp(-2 * PI * (80.0 + 220.0 * this.b) / rate)
            }
            Voice.ORIGINAL -> Unit
        }
    }

    private fun setLength(scaleNow: Double) {
        if (scaleNow == lengthScale) return
        lengthScale = scaleNow
        for (i in combsLeft.indices) {
            combsLeft[i].length = ((combBase[i] * scale * scaleNow).toInt()).coerceIn(8, combsLeft[i].buffer.size)
            combsRight[i].length = (((combBase[i] + 23) * scale * scaleNow).toInt()).coerceIn(8, combsRight[i].buffer.size)
        }
        clear()
    }

    fun clear() {
        for (comb in combsLeft) comb.clear()
        for (comb in combsRight) comb.clear()
        for (all in allLeft) all.buffer.fill(0f)
        for (all in allRight) all.buffer.fill(0f)
        delay.fill(0f); haas.fill(0f); write = 0; haasWrite = 0; sideLow = 0.0; haasFilter = 0.0
    }

    /** Takes one stereo frame, and leaves the result in [outLeft] and [outRight]. */
    fun process(l: Float, r: Float) {
        when (voice) {
            Voice.SPACE, Voice.AMBIENCE -> room(l, r)
            Voice.WIDE -> widen(l, r)
            Voice.ORIGINAL -> { outLeft = l; outRight = r }
        }
    }

    private fun room(l: Float, r: Float) {
        val mono = (l + r) * .5f
        delay[write] = mono
        val at = (write - predelay + delayLength) % delayLength
        val fed = delay[at] * .03f
        var tailLeft = 0f; var tailRight = 0f
        for (i in combsLeft.indices) { tailLeft += combsLeft[i].run(fed, feedback, damp); tailRight += combsRight[i].run(fed, feedback, damp) }
        for (all in allLeft) tailLeft = all.run(tailLeft)
        for (all in allRight) tailRight = all.run(tailRight)
        var reflectionsLeft = 0f; var reflectionsRight = 0f
        if (early > 0f) for (i in tapsLeftMs.indices) {
            reflectionsLeft += delay[(write - predelay - (tapsLeftMs[i] * rate / 1000).toInt() + delayLength * 2) % delayLength] * tapGain[i]
            reflectionsRight += delay[(write - predelay - (tapsRightMs[i] * rate / 1000).toInt() + delayLength * 2) % delayLength] * tapGain[i]
        }
        write = (write + 1) % delayLength
        outLeft = l + tailLeft * wet * 3f + reflectionsLeft * early * .6f
        outRight = r + tailRight * wet * 3f + reflectionsRight * early * .6f
    }

    private fun widen(l: Float, r: Float) {
        val mid = (l + r) * .5f
        val side = (l - r) * .5f
        // The lows of the sides are taken out, so that the bass stays in the middle however wide the image is.
        sideLow += (side - sideLow) * lowCoefficient
        var highSide = (side - sideLow.toFloat()) * width
        // A short copy of the middle, without its lows, put in the sides: the ears hear it as depth.
        haas[haasWrite] = mid
        val delayed = haas[(haasWrite + 1) % haas.size]
        haasWrite = (haasWrite + 1) % haas.size
        haasFilter += (delayed - haasFilter) * .1
        highSide += (delayed - haasFilter.toFloat()) * depth
        outLeft = mid + highSide
        outRight = mid - highSide
    }
}
