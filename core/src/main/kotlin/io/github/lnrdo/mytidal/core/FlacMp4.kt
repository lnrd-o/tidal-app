package io.github.lnrdo.mytidal.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream

/** Clear, single-track FLAC in ISO BMFF, as specified in Xiph's isoflac mapping. */
object FlacMp4 {
    private const val MAX_SEGMENT = 256L * 1024 * 1024
    private data class Box(val type: String, val payload: Int, val end: Int)

    /** A cover picture for the FLAC file. [mime] is the type of the image, such as image/jpeg. */
    class Picture(val mime: String, val data: ByteArray)

    fun flacHeader(initialization: ByteArray, tags: Map<String, String> = emptyMap(), picture: Picture? = null): ByteArray {
        if (initialization.size > 2 * 1024 * 1024) throw IOException("The audio initialization is too large.")
        val top = boxes(initialization, 0, initialization.size)
        val moov = only(top, "moov")
        val trak = only(boxes(initialization, moov.payload, moov.end), "trak")
        val mdia = child(initialization, trak, "mdia")
        val handler = child(initialization, mdia, "hdlr")
        if (handler.end - handler.payload < 12 || ascii(initialization, handler.payload + 8, 4) != "soun") throw IOException("The download is not a single audio track.")
        val stsd = child(initialization, child(initialization, child(initialization, mdia, "minf"), "stbl"), "stsd")
        if (stsd.end - stsd.payload < 8 || uint32(initialization, stsd.payload + 4) != 1L) throw IOException("The download has multiple sample descriptions.")
        val entry = boxes(initialization, stsd.payload + 8, stsd.end).singleOrNull()
            ?.takeIf { it.type == "fLaC" } ?: throw IOException("The download does not contain clear FLAC samples.")
        if (entry.end - entry.payload < 28 || initialization[entry.payload + 8] != 0.toByte() || initialization[entry.payload + 9] != 0.toByte()) throw IOException("Unsupported audio sample entry.")
        val config = only(boxes(initialization, entry.payload + 28, entry.end), "dfLa")
        if (config.end - config.payload < 42 || uint32(initialization, config.payload) != 0L) throw IOException("Unsupported FLAC configuration.")
        val metadata = initialization.copyOfRange(config.payload + 4, config.end)
        var position = 0
        val blocks = mutableListOf<ByteArray>()
        while (position < metadata.size) {
            if (metadata.size - position < 4) throw IOException("Truncated FLAC metadata.")
            val type = metadata[position].toInt() and 0x7f
            val length = ((metadata[position + 1].toInt() and 255) shl 16) or
                ((metadata[position + 2].toInt() and 255) shl 8) or (metadata[position + 3].toInt() and 255)
            if ((position == 0 && (type != 0 || length != 34)) || (position > 0 && type == 0) || type == 127 || length > metadata.size - position - 4) throw IOException("Invalid FLAC metadata block.")
            if ((tags.isEmpty() || type != 4) && (picture == null || type != 6)) blocks += metadata.copyOfRange(position, position + length + 4).also { it[0] = type.toByte() }
            position += length + 4
        }
        if (tags.isNotEmpty()) blocks += comments(tags)
        if (picture != null) blocks += pictureBlock(picture)
        blocks.last()[0] = (blocks.last()[0].toInt() or 0x80).toByte()
        return ByteArrayOutputStream().apply { write("fLaC".toByteArray(Charsets.US_ASCII)); blocks.forEach(::write) }.toByteArray()
    }

    /** The front cover as a FLAC picture block. The size of the picture is not known to the header, so the fields for it stay zero. */
    private fun pictureBlock(picture: Picture): ByteArray {
        if (picture.data.size !in 1..8 * 1024 * 1024 || picture.mime.length !in 1..64 || !picture.mime.all { it.code in 32..126 }) throw IOException("The cover picture is not usable.")
        val payload = ByteArrayOutputStream()
        fun integer(value: Int) { repeat(4) { payload.write(value ushr (24 - it * 8)) } }
        integer(3)
        integer(picture.mime.length); payload.write(picture.mime.toByteArray(Charsets.US_ASCII))
        integer(0)
        repeat(4) { integer(0) }
        integer(picture.data.size); payload.write(picture.data)
        val bytes = payload.toByteArray()
        return byteArrayOf(6, (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes
    }

    private fun comments(tags: Map<String, String>): ByteArray {
        require(tags.size <= 64 && tags.keys.all { it.matches(Regex("[A-Z0-9_]+")) })
        val payload = ByteArrayOutputStream()
        fun integer(value: Int) { repeat(4) { payload.write(value ushr (it * 8)) } }
        fun text(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(bytes.size <= 256 * 1024)
            integer(bytes.size); payload.write(bytes)
        }
        text("OpenTune"); integer(tags.size)
        tags.forEach { (key, value) -> text("$key=$value") }
        val bytes = payload.toByteArray()
        require(bytes.size <= 2 * 1024 * 1024)
        return byteArrayOf(4, (bytes.size ushr 16).toByte(), (bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes
    }

    fun copyFrames(segment: InputStream, destination: OutputStream): Long {
        var copied = 0L
        var consumed = 0L
        while (true) {
            val first = segment.read()
            if (first < 0) break
            val header = ByteArray(8)
            header[0] = first.toByte()
            readExactly(segment, header, 1, 7)
            val type = ascii(header, 4, 4)
            var size = uint32(header, 0)
            var headerSize = 8
            if (size == 1L) {
                val extended = ByteArray(8)
                readExactly(segment, extended, 0, 8)
                size = extended.fold(0L) { n, byte -> (n shl 8) or (byte.toLong() and 255) }
                headerSize = 16
            }
            if (size != 0L && (size < headerSize || size > MAX_SEGMENT - consumed)) throw IOException("Invalid MP4 segment box size.")
            val length = if (size == 0L) null else size - headerSize
            if (type == "mdat") {
                if (length != null && length < 2) throw IOException("The audio segment is empty.")
                val a = segment.read(); val b = segment.read()
                if (a != 255 || b < 0 || (b and 254) != 248) throw IOException("The audio segment does not start with a FLAC frame.")
                destination.write(a); destination.write(b)
                copied += 2 + transfer(segment, destination, length?.minus(2), MAX_SEGMENT - consumed - headerSize - 2)
            } else transfer(segment, null, length, MAX_SEGMENT - consumed - headerSize)
            if (size == 0L) break
            consumed += size
        }
        if (copied == 0L) throw IOException("The audio segment contains no frames.")
        return copied
    }

    private fun transfer(input: InputStream, output: OutputStream?, length: Long?, maximum: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (length == null || total < length) {
            val wanted = if (length == null) buffer.size else minOf(buffer.size.toLong(), length - total).toInt()
            val count = input.read(buffer, 0, wanted)
            if (count < 0) { if (length != null) throw EOFException("Truncated MP4 segment."); break }
            if (count == 0) continue
            total += count
            if (total > maximum) throw IOException("The audio segment exceeds the supported size.")
            output?.write(buffer, 0, count)
        }
        return total
    }

    private fun boxes(bytes: ByteArray, from: Int, to: Int): List<Box> = buildList {
        var position = from
        while (position < to) {
            if (to - position < 8) throw IOException("Truncated MP4 box.")
            var length = uint32(bytes, position)
            val type = ascii(bytes, position + 4, 4)
            var header = 8
            if (length == 1L) {
                if (to - position < 16) throw IOException("Truncated MP4 box.")
                length = (position + 8 until position + 16).fold(0L) { n, i -> (n shl 8) or (bytes[i].toLong() and 255) }
                header = 16
            }
            if (length == 0L) length = (to - position).toLong()
            if (length < header || length > to - position) throw IOException("Invalid MP4 box size.")
            val end = position + length.toInt()
            add(Box(type, position + header, end))
            position = end
        }
    }

    private fun child(bytes: ByteArray, parent: Box, type: String) = only(boxes(bytes, parent.payload, parent.end), type)
    private fun only(boxes: List<Box>, type: String) = boxes.filter { it.type == type }.singleOrNull() ?: throw IOException("Expected one $type box.")
    private fun ascii(bytes: ByteArray, start: Int, size: Int) = String(bytes, start, size, Charsets.US_ASCII)
    private fun uint32(bytes: ByteArray, offset: Int): Long = (offset until offset + 4).fold(0L) { n, i -> (n shl 8) or (bytes[i].toLong() and 255) }
    private fun readExactly(input: InputStream, bytes: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val count = input.read(bytes, offset + read, length - read)
            if (count < 0) throw EOFException("Truncated MP4 segment.")
            read += count
        }
    }
}
