package io.github.lnrdo.mytidal.core

import java.util.Locale

/** The format of an audio file, as far as its header tells. */
data class AudioFormatInfo(val codec: String, val bitDepth: Int? = null, val sampleRate: Int? = null, val bitrateKbps: Int? = null) {
    /** The label that the player shows, in the same form as the labels that TIDAL streams carry: "FLAC · 24-bit · 96.0 kHz". */
    val label: String get() = listOfNotNull(codec.takeIf(String::isNotBlank), bitDepth?.let { "$it-bit" },
        sampleRate?.let { String.format(Locale.ROOT, "%.1f kHz", it / 1000.0) }, bitrateKbps?.let { "$it kbps" }).joinToString(" · ")
}

/** Reads the stream properties from the first bytes of a file. */
object AudioHeaders {
    /** Reads the STREAMINFO block of a FLAC file. [head] holds the first bytes of the file. */
    fun flac(head: ByteArray): AudioFormatInfo? {
        if (head.size < 26 || String(head, 0, 4, Charsets.ISO_8859_1) != "fLaC") return null
        // The first metadata block is STREAMINFO. Its data starts at byte 8 and holds the sample rate in 20 bits, the channels in 3 and the bit depth in 5.
        if (head[4].toInt() and 0x7F != 0) return null
        val b = { index: Int -> head[index].toInt() and 0xFF }
        val rate = (b(18) shl 12) or (b(19) shl 4) or (b(20) ushr 4)
        val bits = (((b(20) and 1) shl 4) or (b(21) ushr 4)) + 1
        return AudioFormatInfo("FLAC", bits, rate.takeIf { it > 0 })
    }

    /** Reads the format chunk of a WAV file. */
    fun wav(head: ByteArray): AudioFormatInfo? {
        if (head.size < 36 || String(head, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(head, 8, 4, Charsets.ISO_8859_1) != "WAVE") return null
        var position = 12
        while (position + 8 <= head.size) {
            val name = String(head, position, 4, Charsets.ISO_8859_1)
            val size = (head[position + 4].toInt() and 0xFF) or ((head[position + 5].toInt() and 0xFF) shl 8) or ((head[position + 6].toInt() and 0xFF) shl 16) or ((head[position + 7].toInt() and 0xFF) shl 24)
            if (name == "fmt " && position + 8 + 16 <= head.size) {
                val at = position + 8
                val rate = (head[at + 4].toInt() and 0xFF) or ((head[at + 5].toInt() and 0xFF) shl 8) or ((head[at + 6].toInt() and 0xFF) shl 16) or ((head[at + 7].toInt() and 0xFF) shl 24)
                val bits = (head[at + 14].toInt() and 0xFF) or ((head[at + 15].toInt() and 0xFF) shl 8)
                return AudioFormatInfo("WAV", bits.takeIf { it > 0 }, rate.takeIf { it > 0 })
            }
            if (size < 0) return null
            position += 8 + size + (size and 1)
        }
        return null
    }
}
