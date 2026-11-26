package io.github.lnrdo.mytidal.core.effects

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** How images are drawn: unchanged, as a halftone of dots, as ordered dither in two colours, or as a mosaic of pixel dots. */
enum class ImageStyle(val id: String) {
    NONE("none"), HALFTONE("halftone"), DITHER("dither"), PIXEL("pixel");

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: NONE }
}

/** The shape of a halftone dot or of a mosaic cell. */
enum class DotShape(val id: String) {
    CIRCLE("circle"), SQUARE("square"), DIAMOND("diamond");

    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: CIRCLE }
}

/**
 * The options of an image effect. [cellSize] is in pixels of the output; [contrast] multiplies the distance of each brightness from the middle and
 * [brightness] (-80..80, in steps of the 0..255 scale) moves it; [gridAngle] turns the halftone grid. With [duotone] the image is drawn in [shadow] and
 * [light] (ARGB); without it, in its own colours.
 */
data class EffectOptions(
    val style: ImageStyle = ImageStyle.NONE,
    val cellSize: Int = 8,
    val contrast: Float = 1.2f,
    val brightness: Int = 0,
    val gridAngle: Int = 0,
    val shape: DotShape = DotShape.CIRCLE,
    val duotone: Boolean = true,
    val shadow: Int = 0xFF0A0A0A.toInt(),
    val light: Int = 0xFFF2F2F2.toInt(),
) {
    /** The options within their ranges. */
    fun clamped() = copy(cellSize = cellSize.coerceIn(MIN_CELL, MAX_CELL), contrast = contrast.coerceIn(.4f, 3f), brightness = brightness.coerceIn(-80, 80),
        gridAngle = gridAngle.coerceIn(0, 90))

    /** A key that changes when the look changes, for caches. */
    val key: String get() = "$style/$cellSize/$contrast/$brightness/$gridAngle/$shape/$duotone/$shadow/$light"

    companion object {
        const val MIN_CELL = 2
        const val MAX_CELL = 40
    }
}

/** A named set of options that the listener can start from. */
class EffectPreset(val id: String, val options: EffectOptions)

/**
 * The image effects, as pure functions on ARGB pixels: the same pixels and options always give the same result. Each mode is per pixel or per cell, so a
 * GPU shader can compute the same thing; this is the reference and the fallback where no shader runs.
 */
object ImageEffects {
    /** The 4×4 Bayer matrix of ordered dither. */
    val BAYER = arrayOf(intArrayOf(0, 8, 2, 10), intArrayOf(12, 4, 14, 6), intArrayOf(3, 11, 1, 9), intArrayOf(15, 7, 13, 5))

    /** The share of a mosaic cell that its square or dot fills, so that a dark grid shows between cells. */
    const val PIXEL_FILL = .78f

    /** The largest halftone dot, as a share of the cell: above half the cell, bright neighbours merge. */
    const val HALFTONE_MAX = .72f

    val PRESETS = listOf(
        EffectPreset("blue_dither", EffectOptions(ImageStyle.DITHER, cellSize = 6, shadow = 0xFF2F5BFF.toInt(), light = 0xFFFFFFFF.toInt())),
        EffectPreset("beige_pixel", EffectOptions(ImageStyle.PIXEL, cellSize = 10, shape = DotShape.SQUARE, shadow = 0xFF6C8488.toInt(), light = 0xFFE8D7AE.toInt())),
        EffectPreset("black_dot", EffectOptions(ImageStyle.HALFTONE, cellSize = 9, gridAngle = 45, shadow = 0xFF0A0A0A.toInt(), light = 0xFFF2F2F2.toInt())),
    )

    /** The brightness of a colour from 0 to 1 (Rec. 709 weights), after [contrast] around the middle and the shift of [brightness]. */
    fun luma(argb: Int, contrast: Float = 1f, brightness: Int = 0): Float {
        val r = (argb shr 16 and 255) / 255f
        val g = (argb shr 8 and 255) / 255f
        val b = (argb and 255) / 255f
        val l = .2126f * r + .7152f * g + .0722f * b
        return ((l - .5f) * contrast + .5f + brightness / 255f).coerceIn(0f, 1f)
    }

    /** The Bayer threshold at a pixel: the matrix is sampled in grains of a quarter of [cellSize], so the grain grows with the cell. */
    fun bayerThreshold(x: Int, y: Int, cellSize: Int): Float {
        val grain = max(1f, cellSize / 4f)
        val bx = floor(x / grain).toInt() and 3
        val by = floor(y / grain).toInt() and 3
        return (BAYER[by][bx] + .5f) / 16f
    }

    /**
     * The Bayer matrix by arithmetic, as the GPU shader computes it (it has no arrays to index): the 4×4 matrix is four copies of the 2×2 one,
     * b2(x, y) = 2·|x − y| + y over bits, so b4(x, y) = 4·b2(x mod 2, y mod 2) + b2(⌊x/2⌋ mod 2, ⌊y/2⌋ mod 2).
     */
    fun bayerFormula(x: Int, y: Int): Int {
        fun b2(a: Int, b: Int) = 2 * abs(a - b) + b
        return 4 * b2(x and 1, y and 1) + b2(x shr 1 and 1, y shr 1 and 1)
    }

    /** The relative luminance of a colour, as WCAG defines it. */
    fun relativeLuminance(argb: Int): Double {
        fun lin(c: Int): Double { val s = c / 255.0; return if (s <= .03928) s / 12.92 else Math.pow((s + .055) / 1.055, 2.4) }
        return .2126 * lin(argb shr 16 and 255) + .7152 * lin(argb shr 8 and 255) + .0722 * lin(argb and 255)
    }

    /** The WCAG contrast ratio of two colours, from 1 to 21. */
    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a); val lb = relativeLuminance(b)
        return (maxOf(la, lb) + .05) / (minOf(la, lb) + .05)
    }

    /**
     * Two colours of a duotone that text drawn through the effect can be read in: when they are closer than the 4.5:1 of WCAG AA, the darker one is
     * moved toward black and the lighter toward white, step by step, until they are not.
     */
    fun readable(shadow: Int, light: Int): Pair<Int, Int> {
        var dark = if (relativeLuminance(shadow) <= relativeLuminance(light)) shadow else light
        var bright = if (dark == shadow) light else shadow
        var step = 0
        while (contrastRatio(dark, bright) < 4.5 && step < 40) {
            dark = mix(dark, 0xFF000000.toInt(), .1f); bright = mix(bright, 0xFFFFFFFF.toInt(), .1f); step++
        }
        return if (relativeLuminance(shadow) <= relativeLuminance(light)) dark to bright else bright to dark
    }

    /** Whether ordered dither lights a pixel of brightness [luma]. */
    fun ditherOn(luma: Float, x: Int, y: Int, cellSize: Int): Boolean = luma > bayerThreshold(x, y, cellSize)

    /** The radius of a halftone dot: the area of the dot follows the brightness, so the radius follows its square root. */
    fun halftoneRadius(brightness: Float, cellSize: Float): Float = HALFTONE_MAX * cellSize * sqrt(brightness.coerceIn(0f, 1f))

    /** Whether a point at ([dx], [dy]) from the centre of a dot of [radius] is inside it, for each shape. */
    fun inside(dx: Float, dy: Float, radius: Float, shape: DotShape): Boolean = when (shape) {
        DotShape.CIRCLE -> dx * dx + dy * dy <= radius * radius
        DotShape.SQUARE -> max(abs(dx), abs(dy)) <= radius * .886f // the square of the same area as the circle
        DotShape.DIAMOND -> abs(dx) + abs(dy) <= radius * 1.2533f // the diamond of the same area as the circle
    }

    /** A colour between [a] and [b]. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((a shr shift and 255) * (1 - u) + (b shr shift and 255) * u).roundToInt() and 255) shl shift
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    /** Draws [pixels] ([width] × [height], ARGB) with [options] (the cell in pixels of these pixels) and returns the new pixels. NONE returns a copy. */
    fun render(pixels: IntArray, width: Int, height: Int, options: EffectOptions): IntArray {
        // The cell may be larger than the setting allows: the setting is in density-independent pixels, and a dense screen multiplies it.
        val o = options.clamped().copy(cellSize = options.cellSize.coerceAtLeast(1))
        return when (o.style) {
            ImageStyle.NONE -> pixels.copyOf()
            ImageStyle.HALFTONE -> halftone(pixels, width, height, o)
            ImageStyle.DITHER -> dither(pixels, width, height, o)
            ImageStyle.PIXEL -> pixel(pixels, width, height, o)
        }
    }

    /** Ordered dither: each pixel is the light colour where it is brighter than the Bayer threshold, else the shadow colour. Two colours only. */
    fun dither(pixels: IntArray, width: Int, height: Int, o: EffectOptions): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val c = pixels[y * width + x]
            val on = ditherOn(luma(c, o.contrast, o.brightness), x, y, o.cellSize)
            out[y * width + x] = if (o.duotone) (if (on) o.light else o.shadow) else (if (on) c or (0xFF shl 24) else o.shadow)
        }
        return out
    }

    /**
     * Halftone: a grid of cells, turned by the angle; each cell draws one hard dot whose radius follows the brightness of the cell, sampled on 3×3 points
     * so that fine detail does not alias. The brightness is said only by the size of the dots.
     */
    fun halftone(pixels: IntArray, width: Int, height: Int, o: EffectOptions): IntArray {
        val out = IntArray(width * height)
        val cell = o.cellSize.toFloat()
        val angle = Math.toRadians(o.gridAngle.toDouble())
        val cs = cos(angle).toFloat(); val sn = sin(angle).toFloat()
        val cache = HashMap<Long, Pair<Float, Int>>()
        for (y in 0 until height) for (x in 0 until width) {
            // Into the turned grid, find the cell and its centre, then back into the image to sample there.
            val u = x * cs + y * sn
            val v = -x * sn + y * cs
            val ci = floor(u / cell).toInt()
            val cj = floor(v / cell).toInt()
            val cu = (ci + .5f) * cell
            val cv = (cj + .5f) * cell
            val (bright, colour) = cache.getOrPut((ci.toLong() shl 32) or (cj.toLong() and 0xFFFFFFFFL)) { sampleCell(pixels, width, height, cu, cv, cs, sn, cell, o) }
            val r = halftoneRadius(bright, cell)
            val dot = inside(u - cu, v - cv, r, o.shape)
            out[y * width + x] = if (dot) (if (o.duotone) o.light else colour) else o.shadow
        }
        return out
    }

    /** The mean brightness and colour of a cell on 3×3 points around its centre (given in the turned grid). */
    private fun sampleCell(pixels: IntArray, width: Int, height: Int, cu: Float, cv: Float, cs: Float, sn: Float, cell: Float, o: EffectOptions): Pair<Float, Int> {
        var sum = 0f; var r = 0; var g = 0; var b = 0
        for (j in -1..1) for (i in -1..1) {
            val su = cu + i * cell / 3f
            val sv = cv + j * cell / 3f
            val x = (su * cs - sv * sn).roundToInt().coerceIn(0, width - 1)
            val y = (su * sn + sv * cs).roundToInt().coerceIn(0, height - 1)
            val c = pixels[y * width + x]
            sum += luma(c, o.contrast, o.brightness)
            r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255
        }
        return sum / 9f to ((0xFF shl 24) or ((r / 9) shl 16) or ((g / 9) shl 8) or (b / 9))
    }

    /** The mosaic: blocks of the cell size, each drawn as a smaller square or dot in the duotone ramp (or its own colour), on a dark grid. */
    fun pixel(pixels: IntArray, width: Int, height: Int, o: EffectOptions): IntArray {
        val out = IntArray(width * height)
        val cell = o.cellSize
        val grid = mix(0xFF000000.toInt(), o.shadow, .35f)
        val cols = (width + cell - 1) / cell
        val rows = (height + cell - 1) / cell
        val half = cell * PIXEL_FILL / 2f
        for (bj in 0 until rows) for (bi in 0 until cols) {
            val x0 = bi * cell; val y0 = bj * cell
            val x1 = minOf(width, x0 + cell); val y1 = minOf(height, y0 + cell)
            var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (y in y0 until y1) for (x in x0 until x1) { val c = pixels[y * width + x]; r += c shr 16 and 255; g += c shr 8 and 255; b += c and 255; n++ }
            val mean = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
            val colour = if (o.duotone) mix(o.shadow, o.light, luma(mean, o.contrast, o.brightness)) else mean
            val cx = x0 + cell / 2f; val cy = y0 + cell / 2f
            for (y in y0 until y1) for (x in x0 until x1) {
                val dx = x + .5f - cx; val dy = y + .5f - cy
                val hit = if (o.shape == DotShape.SQUARE) abs(dx) <= half && abs(dy) <= half else inside(dx, dy, half, o.shape)
                out[y * width + x] = if (hit) colour else grid
            }
        }
        return out
    }
}
