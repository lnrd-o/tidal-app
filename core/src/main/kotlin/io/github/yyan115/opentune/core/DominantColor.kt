package io.github.yyan115.opentune.core

import kotlin.math.max
import kotlin.math.min

/**
 * The colour that a cover is remembered by: the hue that carries most colour, made bright enough to read on black.
 * Dark, white and grey pixels are not colours of the picture, so they do not count. A cover with no colour in it (black and white) gives null.
 */
object DominantColor {
    private const val HUES = 12

    /** [pixels] are colours as 0xAARRGGBB. Returns a colour as 0xFFRRGGBB, or null when the picture has no colour to speak of. */
    fun of(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        val weight = DoubleArray(HUES)
        val red = DoubleArray(HUES); val green = DoubleArray(HUES); val blue = DoubleArray(HUES)
        var counted = 0
        for (pixel in pixels) {
            if ((pixel ushr 24) < 128) continue
            val r = (pixel shr 16 and 0xFF) / 255.0; val g = (pixel shr 8 and 0xFF) / 255.0; val b = (pixel and 0xFF) / 255.0
            val high = max(r, max(g, b)); val low = min(r, min(g, b))
            val saturation = if (high <= 0.0) 0.0 else (high - low) / high
            if (high < 0.2 || saturation < 0.25) continue
            val bin = (hue(r, g, b, high, low) / 360.0 * HUES).toInt().coerceIn(0, HUES - 1)
            val w = saturation * high
            weight[bin] += w; red[bin] += r * w; green[bin] += g * w; blue[bin] += b * w
            counted++
        }
        // A few coloured pixels in a grey picture are not its colour: at least one pixel in fifty must count.
        if (counted * 50 < pixels.size) return null
        // Two neighbouring hues are one colour, so each bin is judged together with its two neighbours.
        var best = 0; var bestWeight = -1.0
        for (i in 0 until HUES) {
            val sum = weight[(i + HUES - 1) % HUES] + weight[i] + weight[(i + 1) % HUES]
            if (sum > bestWeight) { bestWeight = sum; best = i }
        }
        var r = 0.0; var g = 0.0; var b = 0.0; var total = 0.0
        for (k in -1..1) {
            val i = (best + k + HUES) % HUES
            r += red[i]; g += green[i]; b += blue[i]; total += weight[i]
        }
        if (total <= 0.0) return null
        r /= total; g /= total; b /= total
        // Brightened for a black screen: full value, and enough saturation to stay a colour.
        val high = max(r, max(g, b)); val low = min(r, min(g, b))
        val saturation = (if (high <= 0.0) 0.0 else (high - low) / high).coerceIn(0.55, 0.95)
        val h = hue(r, g, b, high, low)
        return hsvToRgb(h, saturation, 1.0)
    }

    private fun hue(r: Double, g: Double, b: Double, high: Double, low: Double): Double {
        val d = high - low
        if (d <= 0.0) return 0.0
        val h = when (high) { r -> ((g - b) / d) % 6; g -> (b - r) / d + 2; else -> (r - g) / d + 4 } * 60.0
        return if (h < 0) h + 360.0 else h
    }

    private fun hsvToRgb(h: Double, s: Double, v: Double): Int {
        val c = v * s
        val x = c * (1 - Math.abs((h / 60.0) % 2 - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0.0); h < 120 -> Triple(x, c, 0.0); h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c); h < 300 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
        }
        fun channel(value: Double) = Math.round((value + m) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }
}
