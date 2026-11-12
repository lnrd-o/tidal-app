package io.github.yyan115.opentune.core.dj

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The character of the studio glue. Each flavor is a type of circuit, with its own detector, its own attack and release, and its own way of
 * colouring the sound while it works. They are modelled on how these circuits behave, not on any one product.
 */
enum class Flavor {
    /** A smooth, even bus compressor: the default. */
    STUDIO,
    /** An optical cell: slow, smooth and warm, and the longer it works the slower it lets go. */
    OPTO,
    /** A field-effect transistor: very fast attack, high ratios, a little grit that grows with the compression. */
    FET,
    /** A voltage-controlled amplifier: clean, precise and firm, reading the level as RMS. */
    VCA,
    /** A tube with a variable gain: a very soft knee that stiffens as the level rises, slow and rich. */
    VARI_MU,
    /** A diode bridge: punchy and thick, with a hint of transformer saturation. */
    DIODE,
    /** Heavy compression blended quietly under the original, for detail and sustain. */
    PARALLEL
}

/** The kind of tape: how deep the wow and flutter go, how hard it saturates, how dark it sounds and how loud its hiss is. */
enum class TapeType(
    val wow: Double, val flutter: Double, val drive: Double,
    val cutoffTop: Double, val cutoffRange: Double, val hissDb: Double, val lowCut: Double, val dropouts: Boolean,
) {
    /** A clean studio machine: the reference. */
    STUDIO(1.0, 1.0, 1.0, 20_000.0, 8_000.0, -72.0, 0.0, false),
    /** An older machine: warmer, darker and a little less steady. */
    VINTAGE(1.6, 1.4, 1.3, 18_000.0, 9_000.0, -68.0, 0.0, false),
    /** A compact cassette: narrow, hissy, thin in the lows and quick to wobble. */
    CASSETTE(2.2, 2.5, 1.5, 14_000.0, 6_000.0, -60.0, 60.0, false),
    /** A tired tape: heavy wobble, a dull top end and small dropouts in the level. */
    WORN(3.5, 3.0, 1.8, 12_000.0, 5_000.0, -62.0, 0.0, true)
}

/** The state of a record: how often it crackles, how loud its surface is, and whether one scratch comes round at every turn. */
enum class VinylCondition(val pops: Double, val surfaceDb: Double, val loud: Double, val scratch: Boolean) {
    NEW(0.35, -4.0, 0.05, false),
    WORN(1.0, 0.0, 0.12, true),
    DUSTY(2.2, 2.0, 0.04, false)
}

/** Everything the module can be set to, in one piece, so that it can be handed to the audio thread at once. */
data class ElZarConfig(
    val on: Boolean = false,
    val glue: Float = 0f, val tape: Float = 0f, val vinyl: Float = 0f,
    val flavor: Flavor = Flavor.STUDIO,
    val tapeType: TapeType = TapeType.STUDIO,
    val vinylCondition: VinylCondition = VinylCondition.WORN,
    /** True for a record at 45 rpm, false for 33 rpm. */
    val vinylFast: Boolean = false,
    val console: Float = 0f, val tube: Float = 0f, val air: Float = 0f, val clip: Float = 0f,
    /** Compress the centre and the sides apart. */
    val midSide: Boolean = false,
    /** Hear the music without the processing, at the same loudness. */
    val bypass: Boolean = false,
)

/**
 * The tone module: character for the sound, in stages that can be set apart.
 *
 * - **Studio glue** is a gentle stereo-linked compressor in several flavors, which can also work on the centre and the sides apart.
 * - **Console** saturates the lows harder, like a transformer, and **tube** adds the even harmonics of a triode.
 * - **Tape** adds warm saturation, rolls the top end off, and moves the pitch with wow and flutter. Four kinds of tape set how much.
 * - **Air** adds a soft shine to the top, **clip** rounds off the peaks, and **vinyl** adds crackle, surface noise, rumble and a scratch.
 * - **Bypass** swaps the processing for the original at the same loudness, so that a louder sound is not heard as a better one.
 *
 * Every stage is off at 0, and with all of them off nothing is touched. The noise is added last, so that it is not pumped or clipped.
 * The module works on stereo frames at [rate], on one thread.
 */
class ElZar(private val rate: Int, seed: Int = 0x2A5F) {
    var on = false
    /** The amounts of the stages, from 0 to 1. */
    var glue = 0f
    var tape = 0f
    var vinyl = 0f
    var console = 0f
    var tube = 0f
    var air = 0f
    var clip = 0f
    /** The character of the studio glue. */
    var flavor = Flavor.STUDIO
    var tapeType = TapeType.STUDIO
    var vinylCondition = VinylCondition.WORN
    var vinylFast = false
    var midSide = false
    var bypass = false

    val active: Boolean get() = on && (glue > 0f || tape > 0f || vinyl > 0f || console > 0f || tube > 0f || air > 0f || clip > 0f)

    fun apply(c: ElZarConfig) {
        on = c.on; glue = c.glue; tape = c.tape; vinyl = c.vinyl; flavor = c.flavor; tapeType = c.tapeType
        vinylCondition = c.vinylCondition; vinylFast = c.vinylFast; console = c.console; tube = c.tube; air = c.air; clip = c.clip
        midSide = c.midSide; bypass = c.bypass
        if (!c.on) reset()
    }

    /** How much the compressor turns the music down now, in decibels. Read by the screen. */
    @Volatile var reductionDb = 0f
        private set

    private var noise = seed or 1
    private fun random(): Float { var x = noise; x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5); noise = x; return x / 2147483648f }
    private fun unit(): Float = (random() + 1f) * 0.5f

    // The result of the stage that ran last. The stages write here instead of returning a pair, so that nothing is allocated for each sample.
    private var outLeft = 0f
    private var outRight = 0f

    // ---- glue ----
    private class GlueState {
        var peakWindow = 0f
        var power = 0.0
        var reduction = 0.0
        var gainSmoothed = 1.0
        var gainTarget = 1.0
        var control = 0
        fun reset() { peakWindow = 0f; power = 0.0; reduction = 0.0; gainSmoothed = 1.0; gainTarget = 1.0; control = 0 }
    }
    private val glueMain = GlueState()
    private val glueSide = GlueState()
    private val powerCoefficient = exp(-1.0 / (0.010 * rate))
    private var cachedFlavor: Flavor? = null
    private var cachedGlue = -1f
    private var cached: GlueSettings = GlueSettings.of(Flavor.STUDIO, 0f)
    private var smoothing = exp(-1.0 / (0.004 * rate))

    // ---- console, tube and air ----
    private var consoleLowLeft = 0.0; private var consoleLowRight = 0.0
    private val consoleCoefficient = 1 - exp(-2 * PI * 140.0 / rate)
    private var dcLeft = 0.0; private var dcRight = 0.0
    private val dcCoefficient = 1 - exp(-2 * PI * 10.0 / rate)
    private var airLeft = 0.0; private var airRight = 0.0
    private val airCoefficient = 1 - exp(-2 * PI * 5_000.0 / rate)

    // ---- tape ----
    private val delayLeft = FloatArray(2048)
    private val delayRight = FloatArray(2048)
    private var write = 0
    private var wowPhase = 0.0
    private var flutterPhase = 0.0
    private var drift = 0.0
    private var lowLeft = 0.0
    private var lowRight = 0.0
    private var rollLeft = 0.0; private var rollRight = 0.0
    private var dropLeft = 0
    private var dropTotal = 1
    private var dropDepth = 0.0

    // ---- noise ----
    private var pink0 = 0.0; private var pink1 = 0.0; private var pink2 = 0.0
    private var pinkRight0 = 0.0; private var pinkRight1 = 0.0; private var pinkRight2 = 0.0
    private var rumbleLeft = 0.0; private var rumbleRight = 0.0
    private var popLeft = 0
    private var popAmplitude = 0f
    private var popDecay = 0f
    private var popLevel = 0f
    private var popBalance = 1f
    private var revolution = 0

    // ---- loudness match for the bypass ----
    private val meterCoefficient = exp(-1.0 / (0.4 * rate))
    private var dryPower = 0.0
    private var wetPower = 0.0
    private var matchTarget = 1.0
    private var matchGain = 1.0
    private var matchTick = 0

    fun reset() {
        glueMain.reset(); glueSide.reset()
        delayLeft.fill(0f); delayRight.fill(0f); write = 0
        lowLeft = 0.0; lowRight = 0.0; rollLeft = 0.0; rollRight = 0.0; popLeft = 0; reductionDb = 0f; dropLeft = 0
        consoleLowLeft = 0.0; consoleLowRight = 0.0; dcLeft = 0.0; dcRight = 0.0; airLeft = 0.0; airRight = 0.0
        dryPower = 0.0; wetPower = 0.0; matchTarget = 1.0; matchGain = 1.0; matchTick = 0; revolution = 0
    }

    /** Processes [frames] interleaved stereo frames of [buffer] from [offset] frames in place. */
    fun processBlock(buffer: FloatArray, offset: Int, frames: Int) {
        if (!active) return
        val glueSettings = settings()
        val noisy = tape > 0f || vinyl > 0f
        for (i in 0 until frames) {
            val dryLeft = buffer[(offset + i) * 2]
            val dryRight = buffer[(offset + i) * 2 + 1]
            var l = dryLeft
            var r = dryRight
            if (glue > 0f) {
                if (midSide) {
                    val mid = (l + r) * 0.5f
                    val side = (l - r) * 0.5f
                    compress(mid, mid, glueSettings, glueMain, 0.0)
                    val centre = outLeft
                    // The sides are quieter, so they are read 4 dB hotter, and they are pressed apart from the centre.
                    compress(side, side, glueSettings, glueSide, 4.0)
                    val sides = outLeft
                    l = centre + sides; r = centre - sides
                } else { compress(l, r, glueSettings, glueMain, 0.0); l = outLeft; r = outRight }
            }
            if (console > 0f) { console(l, r); l = outLeft; r = outRight }
            if (tube > 0f) { tube(l, r); l = outLeft; r = outRight }
            if (tape > 0f) { tape(l, r); l = outLeft; r = outRight }
            if (air > 0f) { air(l, r); l = outLeft; r = outRight }
            if (clip > 0f) { clip(l, r); l = outLeft; r = outRight }
            if (noisy) { noise(l, r); l = outLeft; r = outRight }
            // The loudness of the original and of the processed sound, read over 0.4 s, gives the gain that makes the two equal.
            dryPower = meterCoefficient * dryPower + (1 - meterCoefficient) * (0.5 * (dryLeft * dryLeft + dryRight * dryRight))
            wetPower = meterCoefficient * wetPower + (1 - meterCoefficient) * (0.5 * (l * l + r * r))
            if (matchTick++ and 63 == 0) matchTarget = if (dryPower < 1e-8) 1.0 else sqrt((wetPower + 1e-12) / dryPower).coerceIn(0.5, 2.0)
            matchGain += (matchTarget - matchGain) * 0.0005
            if (bypass) { l = (dryLeft * matchGain).toFloat(); r = (dryRight * matchGain).toFloat() }
            buffer[(offset + i) * 2] = l
            buffer[(offset + i) * 2 + 1] = r
        }
    }

    // ---- studio glue, in its flavors ----

    private fun settings(): GlueSettings {
        if (flavor != cachedFlavor || glue != cachedGlue) {
            cached = GlueSettings.of(flavor, glue); cachedFlavor = flavor; cachedGlue = glue
            smoothing = exp(-1.0 / (cached.smoothingSeconds * rate))
        }
        return cached
    }

    /** The gain reduction in decibels (zero or above) that a level of [levelDb] calls for. */
    internal fun reductionFor(levelDb: Double, s: GlueSettings): Double {
        val over = levelDb - s.threshold
        val slope = 1.0 - 1.0 / s.ratio
        if (s.variableMu) {
            // The ratio starts at one and grows with the level, over a width of 18 dB: the knee of a variable-gain tube.
            val width = 18.0
            return when { over <= 0.0 -> 0.0; over < width -> 0.5 * slope * over * over / width; else -> slope * (over - width / 2) }
        }
        return when {
            over <= -s.knee / 2 -> 0.0
            s.knee > 0.0 && over < s.knee / 2 -> slope * (over + s.knee / 2).pow(2) / (2 * s.knee)
            else -> slope * over
        }
    }

    private fun compress(l: Float, r: Float, s: GlueSettings, st: GlueState, boostDb: Double) {
        val peak = max(abs(l), abs(r))
        if (peak > st.peakWindow) st.peakWindow = peak
        st.power = powerCoefficient * st.power + (1 - powerCoefficient) * (0.5 * (l * l + r * r))
        if (st.control++ and 15 == 0) {
            // The level is read at a control rate, 16 samples at a time. RMS gets 3 dB added, so that a sine reads as its peak.
            val level = (if (s.rms) 10.0 * log10(max(st.power, 1e-12)) + 3.0 else 20.0 * log10(max(st.peakWindow.toDouble(), 1e-6))) + boostDb
            st.peakWindow = 0f
            val target = reductionFor(level, s)
            val tick = 16.0 / rate
            if (target > st.reduction) st.reduction += (target - st.reduction) * (1 - exp(-tick / s.attack))
            else {
                // A circuit with a memory lets go more slowly the harder it has worked.
                val release = s.releaseFast + (s.releaseSlow - s.releaseFast) * (st.reduction / s.progressRef).coerceIn(0.0, 1.0)
                st.reduction += (target - st.reduction) * (1 - exp(-tick / release))
            }
            // The makeup gain restores the level of ordinary music, so that the effect is felt as glue and not as a drop in volume.
            val makeup = if (s.makeupFixed >= 0.0) s.makeupFixed else (reductionFor(s.reference, s) * 0.9).coerceIn(0.0, 8.0)
            st.gainTarget = 10.0.pow((makeup - st.reduction) / 20.0)
            reductionDb = max(glueMain.reduction, if (midSide) glueSide.reduction else 0.0).toFloat()
        }
        st.gainSmoothed = smoothing * st.gainSmoothed + (1 - smoothing) * st.gainTarget
        val wetGain = st.gainSmoothed.toFloat()
        var wetLeft = l * wetGain
        var wetRight = r * wetGain
        if (s.saturation > 0.0) {
            // The circuit colours the sound while it works: a fixed warmth, or a grit that grows with the compression.
            val drive = s.satDrive + s.satFromReduction * st.reduction
            val weight = (s.saturation * (if (s.satFromReduction > 0.0) (st.reduction / 10.0).coerceIn(0.0, 1.0) else 1.0)).toFloat()
            val norm = drive * (1 - tanh(s.satBias).pow(2))
            val bias = tanh(s.satBias)
            wetLeft = wetLeft * (1 - weight) + weight * ((tanh(drive * wetLeft + s.satBias) - bias) / norm).toFloat()
            wetRight = wetRight * (1 - weight) + weight * ((tanh(drive * wetRight + s.satBias) - bias) / norm).toFloat()
        }
        val mix = s.mix.toFloat()
        outLeft = l * (1 - mix) + wetLeft * mix
        outRight = r * (1 - mix) + wetRight * mix
    }

    // ---- console: the lows saturate harder, like the iron of a transformer ----

    private fun console(inLeft: Float, inRight: Float) {
        val c = console.toDouble()
        consoleLowLeft += (inLeft - consoleLowLeft) * consoleCoefficient
        consoleLowRight += (inRight - consoleLowRight) * consoleCoefficient
        val lowDrive = 1.0 + 3.5 * c
        val wholeDrive = 1.0 + 0.7 * c
        outLeft = shapeConsole(inLeft, consoleLowLeft, lowDrive, wholeDrive, c)
        outRight = shapeConsole(inRight, consoleLowRight, lowDrive, wholeDrive, c)
    }

    private fun shapeConsole(x: Float, low: Double, lowDrive: Double, wholeDrive: Double, c: Double): Float {
        val rest = x - low
        val pushed = rest + tanh(lowDrive * low) / lowDrive
        val soft = tanh(wholeDrive * pushed) / wholeDrive
        return (x * (1 - c) + c * soft).toFloat()
    }

    // ---- tube: a triode biased off centre, so that it makes the even harmonics ----

    private fun tube(inLeft: Float, inRight: Float) {
        val t = tube.toDouble()
        val drive = 1.0 + 2.2 * t
        val bias = 0.25 * t
        val norm = drive * (1 - tanh(bias).pow(2))
        val top = tanh(bias)
        var l = inLeft * (1 - t) + t * ((tanh(drive * inLeft + bias) - top) / norm)
        var r = inRight * (1 - t) + t * ((tanh(drive * inRight + bias) - top) / norm)
        // The bias moves the centre of the wave: a slow filter takes the offset away.
        dcLeft += (l - dcLeft) * dcCoefficient; dcRight += (r - dcRight) * dcCoefficient
        l -= dcLeft; r -= dcRight
        outLeft = l.toFloat(); outRight = r.toFloat()
    }

    // ---- air: the top is lifted a little, and its loud parts are excited ----

    private fun air(inLeft: Float, inRight: Float) {
        val a = air.toDouble()
        airLeft += (inLeft - airLeft) * airCoefficient; airRight += (inRight - airRight) * airCoefficient
        outLeft = (inLeft + a * 0.45 * excite(inLeft - airLeft)).toFloat()
        outRight = (inRight + a * 0.45 * excite(inRight - airRight)).toFloat()
    }

    /** A soft, slightly lopsided curve with a gain of one for small signals. */
    private fun excite(x: Double): Double = (tanh(4.0 * x + 0.3) - tanh(0.3)) / (4.0 * (1 - tanh(0.3).pow(2)))

    // ---- clip: the peaks are rounded off, never cut ----

    private fun clip(inLeft: Float, inRight: Float) {
        val gain = 1.0 + clip.toDouble()
        outLeft = softClip(inLeft * gain).toFloat()
        outRight = softClip(inRight * gain).toFloat()
    }

    private fun softClip(x: Double): Double {
        val a = abs(x)
        if (a <= CLIP_KNEE) return x
        val shaped = CLIP_KNEE + (CLIP_CEILING - CLIP_KNEE) * tanh((a - CLIP_KNEE) / (CLIP_CEILING - CLIP_KNEE))
        return if (x < 0) -shaped else shaped
    }

    // ---- tape ----

    private fun tape(inLeft: Float, inRight: Float) {
        val t = tape.toDouble()
        val kind = tapeType
        // Wow is slow and deep, flutter quick and shallow, and a little drift keeps the two from repeating. The depth is in samples.
        wowPhase += 2 * PI * 0.55 / rate; flutterPhase += 2 * PI * 5.7 / rate
        if (wowPhase > 2 * PI) wowPhase -= 2 * PI
        if (flutterPhase > 2 * PI) flutterPhase -= 2 * PI
        drift += (random() * 0.00002 - drift * 0.00004)
        val depthWow = 0.00022 * rate * t * kind.wow
        val depthFlutter = 0.00003 * rate * t * kind.flutter
        val centre = 0.0025 * rate
        val delay = centre + depthWow * (0.6 * sin(wowPhase) + 0.3 * drift * 200) + depthFlutter * sin(flutterPhase)
        delayLeft[write] = inLeft; delayRight[write] = inRight
        val position = write - delay
        val base = Math.floor(position).toInt()
        val fraction = (position - base).toFloat()
        val mask = delayLeft.size - 1
        val a = base and mask; val b = (base + 1) and mask
        var l = delayLeft[a] * (1 - fraction) + delayLeft[b] * fraction
        var r = delayRight[a] * (1 - fraction) + delayRight[b] * fraction
        write = (write + 1) and mask

        // Saturation with a little asymmetry for even harmonics. The small-signal gain stays at one.
        // It is blended in by the amount, so that a light tape stays light: at zero the signal passes untouched.
        val drive = 1.0 + 1.6 * t * kind.drive
        val bias = 0.1 * t
        val norm = drive * (1 - tanh(bias).pow(2))
        val dry = (1 - t).toFloat()
        l = (l * dry + t * ((tanh(drive * l + bias) - tanh(bias)) / norm)).toFloat()
        r = (r * dry + t * ((tanh(drive * r + bias) - tanh(bias)) / norm)).toFloat()

        // A small machine does not carry the lows.
        if (kind.lowCut > 0.0) {
            val k = 1 - exp(-2 * PI * kind.lowCut / rate)
            rollLeft += (l - rollLeft) * k; rollRight += (r - rollRight) * k
            l -= rollLeft.toFloat(); r -= rollRight.toFloat()
        }

        // The top end softens as the tape gets older.
        val cutoff = kind.cutoffTop - kind.cutoffRange * t
        val a1 = exp(-2 * PI * cutoff / rate)
        lowLeft = (1 - a1) * l + a1 * lowLeft; lowRight = (1 - a1) * r + a1 * lowRight
        var outL = lowLeft; var outR = lowRight

        // A worn tape lets the level dip now and then, for a tenth of a second or so.
        if (kind.dropouts) {
            if (dropLeft <= 0 && unit() < 0.4 * t / rate) { dropTotal = (0.06 * rate + unit() * 0.1 * rate).toInt(); dropLeft = dropTotal; dropDepth = 0.15 + 0.35 * unit() }
            if (dropLeft > 0) {
                val gain = 1.0 - dropDepth * sin(PI * (1.0 - dropLeft.toDouble() / dropTotal))
                outL *= gain; outR *= gain; dropLeft--
            }
        }
        outLeft = outL.toFloat(); outRight = outR.toFloat()
    }

    // ---- noise: tape hiss, vinyl surface, crackle and rumble ----

    private fun dbToLinear(db: Double) = 10.0.pow(db / 20.0)

    private fun pinkNoise(left: Boolean): Double {
        val w = random().toDouble()
        return if (left) {
            pink0 = 0.99765 * pink0 + w * 0.0990460; pink1 = 0.96300 * pink1 + w * 0.2965164; pink2 = 0.57000 * pink2 + w * 1.0526913
            (pink0 + pink1 + pink2 + w * 0.1848) * 0.11
        } else {
            pinkRight0 = 0.99765 * pinkRight0 + w * 0.0990460; pinkRight1 = 0.96300 * pinkRight1 + w * 0.2965164; pinkRight2 = 0.57000 * pinkRight2 + w * 1.0526913
            (pinkRight0 + pinkRight1 + pinkRight2 + w * 0.1848) * 0.11
        }
    }

    private fun startPop(db: Double) {
        popLeft = 20 + (unit() * 60).toInt()
        popAmplitude = dbToLinear(db).toFloat()
        popDecay = (0.9 + 0.07 * unit()).toFloat(); popLevel = 1f
        popBalance = 0.55f + 0.45f * unit()
    }

    private fun noise(inLeft: Float, inRight: Float) {
        var l = inLeft.toDouble(); var r = inRight.toDouble()
        if (tape > 0f) {
            // Tape hiss: very faint, from about -72 dBFS to -64 dBFS for the studio machine, and louder for a cassette.
            val level = dbToLinear(tapeType.hissDb + 8.0 * tape)
            l += pinkNoise(true) * level; r += pinkNoise(false) * level
        }
        if (vinyl > 0f) {
            val v = vinyl.toDouble()
            val condition = vinylCondition
            // Surface noise and a low rumble, about -66 dBFS and -64 dBFS at the full setting. A faster record has a quieter surface.
            val surface = dbToLinear(-78.0 + 12.0 * v + condition.surfaceDb - (if (vinylFast) 2.0 else 0.0))
            l += pinkNoise(true) * surface; r += pinkNoise(false) * surface
            val rumble = dbToLinear(-76.0 + 12.0 * v)
            val rumbleSpeed = if (vinylFast) 0.0047 else 0.0035
            rumbleLeft += (random() - rumbleLeft) * rumbleSpeed; rumbleRight += (random() - rumbleRight) * rumbleSpeed
            l += rumbleLeft * rumble * 18; r += rumbleRight * rumble * 18
            // Crackle: pops arrive at random, 1 to 7 a second. Most are tiny, and a few are loud.
            if (popLeft <= 0 && unit() < (1.0 + 6.0 * v) * condition.pops / rate) {
                val loud = unit() < condition.loud
                val negative = random() < 0f
                startPop((if (loud) -40.0 + 8.0 * unit() else -62.0 + 14.0 * unit()) + 6.0 * v)
                if (negative) popAmplitude = -popAmplitude
            }
            // A scratch comes round once at every turn of the record: every 1.8 s at 33 rpm and every 1.33 s at 45 rpm.
            if (condition.scratch) {
                val turn = (rate * (if (vinylFast) 60.0 / 45.0 else 60.0 / 33.333)).toInt()
                if (++revolution >= turn) {
                    revolution = 0
                    startPop(-46.0 + 8.0 * v); popBalance = 0.8f
                }
            } else revolution = 0
            if (popLeft > 0) {
                val burst = random() * popAmplitude * popLevel
                l += burst; r += burst * popBalance
                popLevel *= popDecay; popLeft--
            }
        }
        outLeft = l.toFloat(); outRight = r.toFloat()
    }

    private companion object {
        const val CLIP_CEILING = 0.89
        const val CLIP_KNEE = 0.6
    }
}

/** The numbers that make one flavor of the glue, for a given amount from 0 to 1. Times are in seconds, levels in decibels. */
internal class GlueSettings(
    val threshold: Double, val ratio: Double, val knee: Double, val variableMu: Boolean,
    val attack: Double, val releaseFast: Double, val releaseSlow: Double, val progressRef: Double,
    val rms: Boolean, val mix: Double,
    val saturation: Double, val satDrive: Double, val satBias: Double, val satFromReduction: Double,
    val reference: Double, val smoothingSeconds: Double,
    /** A fixed makeup gain in decibels, or a negative number to restore the level of ordinary music. */
    val makeupFixed: Double = -1.0,
) {
    companion object {
        fun of(flavor: Flavor, g: Float): GlueSettings {
            val a = g.toDouble()
            return when (flavor) {
                Flavor.STUDIO -> GlueSettings(-10 - 8 * a, 1.5 + 1.5 * a, 8.0, false, 0.025, 0.18, 0.18, 1.0, false, 0.35 + 0.45 * a, 0.0, 1.0, 0.0, 0.0, -14.0, 0.004)
                Flavor.OPTO -> GlueSettings(-12 - 10 * a, 2.2 + 2.3 * a, 14.0, false, 0.012, 0.06, 1.6, 8.0, true, 0.6 + 0.4 * a, 0.25, 1.3, 0.08, 0.0, -14.0, 0.006)
                Flavor.FET -> GlueSettings(-14 - 10 * a, 4.0 + 16.0 * a, 2.0, false, 0.0003, 0.04, 0.25, 10.0, false, 0.55 + 0.45 * a, 0.6, 1.0, 0.0, 0.12, -16.0, 0.001)
                Flavor.VCA -> GlueSettings(-12 - 10 * a, 2.0 + 4.0 * a, 3.0, false, 0.008, 0.1, 0.5, 8.0, true, 0.5 + 0.5 * a, 0.0, 1.0, 0.0, 0.0, -14.0, 0.003)
                Flavor.VARI_MU -> GlueSettings(-10 - 6 * a, 3.0 + 3.0 * a, 0.0, true, 0.030, 0.3, 1.2, 6.0, false, 0.6 + 0.4 * a, 0.35, 1.4, 0.12, 0.0, -12.0, 0.008)
                Flavor.DIODE -> GlueSettings(-12 - 8 * a, 2.0 + 4.0 * a, 6.0, false, 0.0025, 0.1, 1.1, 8.0, false, 0.5 + 0.5 * a, 0.3, 1.5, 0.06, 0.0, -14.0, 0.003)
                Flavor.PARALLEL -> GlueSettings(-28 - 6 * a, 8.0 + 4.0 * a, 6.0, false, 0.0015, 0.12, 0.12, 1.0, false, 0.15 + 0.4 * a, 0.0, 1.0, 0.0, 0.0, -26.0, 0.002, 14.0)
            }
        }
    }
}
