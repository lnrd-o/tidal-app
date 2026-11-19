package io.github.lnrdo.mytidal.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class FlacMp4Test {
    @Test fun restoresNativeFlacHeaderAndPreservesStreamInfo() {
        val streamInfo = ByteArray(34) { it.toByte() }
        val header = FlacMp4.flacHeader(initialization(streamInfo))
        assertEquals("fLaC", header.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(128, header[4].toInt() and 255)
        assertEquals(34, header[7].toInt())
        assertArrayEquals(streamInfo, header.copyOfRange(8, 42))
    }

    @Test fun addsUtf8VorbisCommentsWithoutChangingStreamInfo() {
        val streamInfo = ByteArray(34) { (it * 3).toByte() }
        val header = FlacMp4.flacHeader(initialization(streamInfo), linkedMapOf("TITLE" to "夜の散歩", "ARTIST" to "Original recording"))
        assertEquals(0, header[4].toInt())
        assertArrayEquals(streamInfo, header.copyOfRange(8, 42))
        assertEquals(132, header[42].toInt() and 255)
        val comments = ByteBuffer.wrap(header.copyOfRange(46, header.size)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val vendor = ByteArray(comments.int).also(comments::get)
        assertEquals("OpenTune", vendor.toString(Charsets.UTF_8))
        assertEquals(2, comments.int)
        assertEquals("TITLE=夜の散歩", ByteArray(comments.int).also(comments::get).toString(Charsets.UTF_8))
        assertEquals("ARTIST=Original recording", ByteArray(comments.int).also(comments::get).toString(Charsets.UTF_8))
    }

    @Test fun addsTheCoverAsTheLastBlockAfterTheComments() {
        val streamInfo = ByteArray(34) { it.toByte() }
        val cover = ByteArray(300) { (it % 251).toByte() }
        val header = FlacMp4.flacHeader(initialization(streamInfo), linkedMapOf("TITLE" to "Night Walk"), FlacMp4.Picture("image/jpeg", cover))
        val buffer = ByteBuffer.wrap(header)
        buffer.position(4)
        val types = mutableListOf<Int>()
        var last = false
        var picture: ByteArray? = null
        while (!last) {
            val flag = buffer.get().toInt() and 255
            last = flag and 0x80 != 0
            val type = flag and 0x7f
            val length = ((buffer.get().toInt() and 255) shl 16) or ((buffer.get().toInt() and 255) shl 8) or (buffer.get().toInt() and 255)
            val body = ByteArray(length).also(buffer::get)
            types += type
            if (type == 6) picture = body
        }
        assertEquals(listOf(0, 4, 6), types)
        assertEquals("The picture block ends the header", header.size, buffer.position())
        val block = ByteBuffer.wrap(picture!!)
        assertEquals(3, block.int)
        assertEquals("image/jpeg", ByteArray(block.int).also(block::get).toString(Charsets.US_ASCII))
        assertEquals(0, block.int)
        repeat(4) { assertEquals(0, block.int) }
        assertEquals(cover.size, block.int)
        assertArrayEquals(cover, ByteArray(block.remaining()).also(block::get))
    }

    @Test fun refusesAnEmptyOrOversizedCover() {
        val init = initialization(ByteArray(34))
        expectInvalid { FlacMp4.flacHeader(init, linkedMapOf("TITLE" to "x"), FlacMp4.Picture("image/jpeg", ByteArray(0))) }
    }

    @Test fun copiesOnlyFlacFramesFromMp4MediaBoxes() {
        val first = byteArrayOf(255.toByte(), 248.toByte(), 1, 2, 3, 4)
        val second = byteArrayOf(255.toByte(), 249.toByte(), 5, 6, 7)
        val segment = box("styp", ByteArray(8)) + box("moof", ByteArray(12)) + box("mdat", first) + box("free", ByteArray(3)) + box("mdat", second)
        val output = ByteArrayOutputStream()
        assertEquals(11L, FlacMp4.copyFrames(ByteArrayInputStream(segment), output))
        assertArrayEquals(first + second, output.toByteArray())
    }

    @Test fun supportsExtendedAndEndOfStreamMediaBoxes() {
        val frame = byteArrayOf(255.toByte(), 248.toByte(), 9, 8, 7)
        val extended = ByteBuffer.allocate(16).putInt(1).put("mdat".toByteArray()).putLong(16L + frame.size).array() + frame
        val unbounded = ByteBuffer.allocate(8).putInt(0).put("mdat".toByteArray()).array() + frame
        for (segment in listOf(extended, unbounded)) {
            val output = ByteArrayOutputStream()
            FlacMp4.copyFrames(ByteArrayInputStream(segment), output)
            assertArrayEquals(frame, output.toByteArray())
        }
    }

    @Test fun rejectsTruncationEncryptedEntriesAndNonFlacPayloads() {
        val init = initialization(ByteArray(34))
        expectInvalid { FlacMp4.flacHeader(init.copyOf(init.size - 1)) }
        expectInvalid { FlacMp4.flacHeader(initialization(ByteArray(34), "enca")) }
        expectInvalid { FlacMp4.copyFrames(ByteArrayInputStream(box("mdat", byteArrayOf(1, 2, 3))), ByteArrayOutputStream()) }
        val oversized = ByteBuffer.allocate(8).putInt(Int.MAX_VALUE).put("mdat".toByteArray()).array()
        expectInvalid { FlacMp4.copyFrames(ByteArrayInputStream(oversized), ByteArrayOutputStream()) }
        val truncated = box("mdat", byteArrayOf(255.toByte(), 248.toByte(), 1)).dropLast(1).toByteArray()
        expectInvalid { FlacMp4.copyFrames(ByteArrayInputStream(truncated), ByteArrayOutputStream()) }
    }

    private fun initialization(streamInfo: ByteArray, sampleType: String = "fLaC"): ByteArray {
        val config = box("dfLa", ByteArray(4) + byteArrayOf(0, 0, 0, 34) + streamInfo)
        val entry = box(sampleType, ByteArray(28) + config)
        val stsd = box("stsd", ByteArray(4) + byteArrayOf(0, 0, 0, 1) + entry)
        val handler = box("hdlr", ByteArray(8) + "soun".toByteArray() + ByteArray(4))
        return box("ftyp", "iso6".toByteArray()) + box("moov", box("trak", box("mdia", handler + box("minf", box("stbl", stsd)))))
    }
    private fun box(type: String, bytes: ByteArray) = ByteBuffer.allocate(8 + bytes.size).putInt(8 + bytes.size).put(type.toByteArray(Charsets.US_ASCII)).put(bytes).array()
    private fun expectInvalid(action: () -> Unit) {
        try { action(); fail("Invalid container was accepted") } catch (_: IOException) { }
    }
}
