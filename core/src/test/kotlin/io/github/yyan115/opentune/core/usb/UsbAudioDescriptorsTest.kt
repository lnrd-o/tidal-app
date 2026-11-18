package io.github.yyan115.opentune.core.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioDescriptorsTest {
    private fun bytes(vararg v: Int) = v.map { it.toByte() }

    /** A class 1 DAC: a control interface with a feature unit that has a master volume, and one streaming setting of 24-bit stereo at 44.1 and 48 kHz. */
    private fun uac1(): ByteArray = (
        bytes(18, 1, 0x00, 0x02, 0, 0, 0, 64, 0xac, 0x05, 0x0a, 0x11, 0, 1, 1, 2, 3, 1) + // device
        bytes(9, 2, 0, 0, 2, 1, 0, 0x80, 50) + // configuration
        bytes(9, 4, 0, 0, 0, 1, 1, 0, 0) + // interface 0: audio control
        bytes(9, 0x24, 1, 0x00, 0x01, 30, 0, 1, 1) + // header, class 1.0
        bytes(12, 0x24, 2, 1, 0x01, 0x01, 0, 2, 3, 0, 0, 0) + // input terminal 1: USB streaming
        bytes(9, 0x24, 6, 2, 1, 1, 0x03, 0x00, 0) + // feature unit 2 from terminal 1: master mute and volume
        bytes(9, 0x24, 3, 3, 0x02, 0x03, 0, 2, 0) + // output terminal 3: headphones
        bytes(9, 4, 1, 0, 0, 1, 2, 0, 0) + // interface 1, alternate 0: zero bandwidth
        bytes(9, 4, 1, 1, 1, 1, 2, 0, 0) + // interface 1, alternate 1
        bytes(7, 0x24, 1, 1, 1, 0x01, 0x00) + // AS general, terminal link 1, PCM
        bytes(14, 0x24, 2, 1, 2, 3, 24, 2, 0x44, 0xac, 0x00, 0x80, 0xbb, 0x00) + // format type I: 2 ch, 3 bytes, 24 bits, 44.1 and 48 kHz
        bytes(9, 5, 0x01, 0x09, 0x26, 0x01, 1, 0, 0) // endpoint 1 OUT, isochronous adaptive, 294 bytes
    ).toByteArray()

    @Test fun aClassOneDacIsRead() {
        val info = UsbAudioDescriptors.parse(uac1())!!
        assertFalse(info.uac2)
        assertEquals(0, info.controlInterface)
        val s = info.playback.single()
        assertEquals(1, s.interfaceNumber); assertEquals(1, s.alternate)
        assertEquals(2, s.channels); assertEquals(3, s.subslotBytes); assertEquals(24, s.bits); assertEquals(6, s.frameBytes)
        assertEquals(listOf(44_100, 48_000), s.rates)
        assertEquals(0x01, s.endpoint); assertEquals(294, s.maxPacket); assertEquals(2, s.syncType); assertNull(s.feedbackEndpoint)
        assertEquals(1, s.terminalLink)
        val volume = info.volume[1]!!
        assertEquals(2, volume.unitId); assertTrue(volume.master)
        assertEquals(1, info.settingsFor(48_000, 2).size)
        assertTrue("no 96 kHz", info.settingsFor(96_000, 2).isEmpty())
    }

    /** A class 2 DAC: a clock source that can be set, a terminal on it, and an asynchronous 32-bit setting with a feedback endpoint. */
    private fun uac2(): ByteArray = (
        bytes(18, 1, 0x00, 0x02, 0xef, 2, 1, 64, 0x34, 0x12, 0x78, 0x56, 0, 1, 1, 2, 3, 1) +
        bytes(9, 2, 0, 0, 2, 1, 0, 0x80, 50) +
        bytes(9, 4, 0, 0, 0, 1, 1, 0x20, 0) + // audio control, protocol 2.0
        bytes(9, 0x24, 1, 0x00, 0x02, 8, 0, 0, 0) + // header, class 2.0
        bytes(8, 0x24, 0x0A, 4, 0x01, 0x07, 0, 0) + // clock source 4, frequency programmable
        bytes(17, 0x24, 2, 1, 0x01, 0x01, 0, 4, 2, 0, 0, 0, 0, 0, 0, 0, 0) + // input terminal 1 on clock 4
        bytes(18, 0x24, 6, 2, 1, 0x0F, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0) + // feature unit 2: master mute and volume, programmable
        bytes(9, 4, 1, 0, 0, 1, 2, 0x20, 0) +
        bytes(9, 4, 1, 1, 2, 1, 2, 0x20, 0) +
        bytes(16, 0x24, 1, 1, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0) + // AS general: terminal 1, PCM, 2 channels
        bytes(6, 0x24, 2, 1, 4, 32) + // format type I: 4-byte slots, 32 bits
        bytes(7, 5, 0x01, 0x05, 0x00, 0x04, 1) + // endpoint 1 OUT, isochronous asynchronous, 1024 bytes
        bytes(7, 5, 0x81, 0x11, 0x04, 0x00, 4) // endpoint 0x81 IN, isochronous feedback, 4 bytes
    ).toByteArray()

    @Test fun aClassTwoDacIsReadWithItsClockAndFeedback() {
        val info = UsbAudioDescriptors.parse(uac2())!!
        assertTrue(info.uac2)
        assertEquals(4, info.clocks[1])
        assertTrue(4 in info.programmableClocks)
        val s = info.playback.single()
        assertEquals(2, s.channels); assertEquals(4, s.subslotBytes); assertEquals(32, s.bits)
        assertNull("a class 2 device names its rates through its clock", s.rates)
        assertEquals(1, s.syncType); assertEquals(0x81, s.feedbackEndpoint); assertEquals(4, s.feedbackMaxPacket)
        assertTrue(info.volume[1]!!.master)
        assertEquals(1, info.settingsFor(96_000, 2, setOf(44_100, 48_000, 96_000)).size)
        assertTrue(info.settingsFor(96_000, 2, setOf(44_100, 48_000)).isEmpty())
    }

    @Test fun onlyTheFirstConfigurationIsRead() {
        // A second configuration, as a class 3 DAC adds after its class 2 one, with a 16-bit setting of its own: it must not show up.
        val first = uac1()
        val second = (bytes(9, 2, 0, 0, 2, 2, 0, 0x80, 50) + bytes(9, 4, 0, 0, 0, 1, 1, 0, 0) + bytes(9, 0x24, 1, 0x00, 0x01, 30, 0, 1, 1) +
            bytes(9, 4, 1, 1, 1, 1, 2, 0, 0) + bytes(7, 0x24, 1, 1, 1, 0x01, 0x00) + bytes(11, 0x24, 2, 1, 2, 2, 16, 1, 0x80, 0xbb, 0x00) + bytes(9, 5, 0x01, 0x09, 0xc0, 0x00, 1, 0, 0)).toByteArray()
        // The first configuration's total length, as a real device gives it: from its configuration descriptor to the end of its last descriptor.
        val total = first.size - 18
        first[20] = (total and 0xFF).toByte(); first[21] = (total shr 8).toByte()
        val info = UsbAudioDescriptors.parse(first + second)!!
        assertEquals(1, info.playback.size)
        assertEquals(24, info.playback.single().bits)
    }

    @Test fun somethingThatIsNotADacIsNotRead() {
        val keyboard = (bytes(18, 1, 0, 2, 0, 0, 0, 8, 1, 2, 3, 4, 0, 1, 1, 2, 0, 1) + bytes(9, 2, 0, 0, 1, 1, 0, 0x80, 50) + bytes(9, 4, 0, 0, 1, 3, 1, 1, 0)).toByteArray()
        assertNull(UsbAudioDescriptors.parse(keyboard))
        assertNull(UsbAudioDescriptors.parse(ByteArray(0)))
    }
}
