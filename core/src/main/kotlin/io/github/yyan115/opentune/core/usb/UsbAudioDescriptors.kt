package io.github.yyan115.opentune.core.usb

/**
 * One way a USB DAC can take audio: an alternate setting of an audio streaming interface with an isochronous OUT endpoint.
 * [rates] is the list of rates that a USB Audio Class 1 device names in its descriptors; a class 2 device names them through its clock, so it is null here.
 */
data class UsbAltSetting(
    val interfaceNumber: Int,
    val alternate: Int,
    val channels: Int,
    /** Bytes that one sample of one channel takes on the wire: 2, 3 or 4. */
    val subslotBytes: Int,
    /** The bits that carry the sample, 16 or 24 for most DACs. */
    val bits: Int,
    val rates: List<Int>?,
    val endpoint: Int,
    /** Bytes per packet, with the extra transactions of a high-bandwidth endpoint counted. */
    val maxPacket: Int,
    val interval: Int,
    /** 1 asynchronous, 2 adaptive, 3 synchronous, 0 none. */
    val syncType: Int,
    /** The isochronous IN endpoint that tells an asynchronous DAC's pace, or null. */
    val feedbackEndpoint: Int?,
    val feedbackMaxPacket: Int,
    val terminalLink: Int,
) {
    val frameBytes: Int get() = channels * subslotBytes
}

/** A feature unit on the way to the output, and whether its master and first two channels have a volume control. */
data class UsbVolumeUnit(val unitId: Int, val master: Boolean, val left: Boolean, val right: Boolean)

/** What a USB audio device offers for playback, read from its configuration descriptors. */
data class UsbAudioInfo(
    val controlInterface: Int,
    /** True for USB Audio Class 2, false for class 1. */
    val uac2: Boolean,
    val playback: List<UsbAltSetting>,
    /** The clock source of each input terminal (class 2 only), by terminal id. */
    val clocks: Map<Int, Int>,
    /** The clock sources whose rate can be set (class 2 only). */
    val programmableClocks: Set<Int>,
    /** The volume unit fed by each input terminal, by terminal id. */
    val volume: Map<Int, UsbVolumeUnit>,
) {
    /** The alternate settings that can play [rate] at [channels], deepest first. A class 2 device is asked for its rates separately. */
    fun settingsFor(rate: Int, channels: Int, classTwoRates: Set<Int>? = null): List<UsbAltSetting> =
        playback.filter { it.channels == channels && (if (uac2) classTwoRates?.contains(rate) ?: false else it.rates?.contains(rate) == true) }.sortedByDescending { it.bits }
}

/** Reads the descriptors that Android hands over ([android.hardware.usb.UsbDeviceConnection.getRawDescriptors]): device, then the active configuration. */
object UsbAudioDescriptors {
    private const val CONFIGURATION = 2
    private const val INTERFACE = 4
    private const val ENDPOINT = 5
    private const val CS_INTERFACE = 0x24
    private const val AUDIO = 1
    private const val CONTROL = 1
    private const val STREAMING = 2

    fun parse(raw: ByteArray): UsbAudioInfo? {
        var at = 0
        var controlInterface = -1
        var uac2 = false
        var current: IntArray? = null // interface, alternate, class, subclass, protocol
        val clocks = HashMap<Int, Int>()
        val programmable = HashSet<Int>()
        val featureSources = ArrayList<Pair<Int, UsbVolumeUnit>>() // source id to unit
        val playback = ArrayList<UsbAltSetting>()
        // The streaming alternate setting being read.
        var link = -1; var channels = 0; var subslot = 0; var bits = 0; var rates: MutableList<Int>? = null
        var endpoint = -1; var maxPacket = 0; var interval = 1; var sync = 0
        var feedback: Int? = null; var feedbackPacket = 0

        fun closeAlt() {
            val i = current ?: return
            if (i[2] == AUDIO && i[3] == STREAMING && endpoint >= 0 && (endpoint and 0x80) == 0 && subslot > 0 && channels > 0) {
                playback.add(UsbAltSetting(i[0], i[1], channels, subslot, bits, if (uac2) null else rates?.toList(), endpoint, maxPacket, interval, sync, feedback, feedbackPacket, link))
            }
            link = -1; channels = 0; subslot = 0; bits = 0; rates = null; endpoint = -1; maxPacket = 0; interval = 1; sync = 0; feedback = null; feedbackPacket = 0
        }

        fun u8(i: Int) = raw[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun u24(i: Int) = u16(i) or (u8(i + 2) shl 16)

        // Android hands over every configuration of the device, and it uses the first. A DAC may have more (a class 3 one after a class 2 one), whose
        // descriptors look alike and would mix with the first's, so the reading stops at the end of the first.
        var end = raw.size
        var configurations = 0
        while (at + 2 <= minOf(raw.size, end)) {
            val length = u8(at)
            if (length < 2 || at + length > raw.size) break
            val type = u8(at + 1)
            val d = at
            if (type == CONFIGURATION) {
                if (++configurations > 1) break
                // A total length shorter than the descriptor itself is not to be trusted: the reading then goes to the next configuration or the end.
                if (length >= 4 && u16(d + 2) >= length) end = minOf(raw.size, d + u16(d + 2))
            }
            when (type) {
                INTERFACE -> if (length >= 9) {
                    closeAlt()
                    current = intArrayOf(u8(d + 2), u8(d + 3), u8(d + 5), u8(d + 6), u8(d + 7))
                    if (u8(d + 5) == AUDIO && u8(d + 6) == CONTROL) { controlInterface = u8(d + 2); if (u8(d + 7) == 0x20) uac2 = true }
                }
                CS_INTERFACE -> {
                    val i = current
                    val subtype = u8(d + 2)
                    if (i != null && i[2] == AUDIO && i[3] == CONTROL) when (subtype) {
                        1 -> if (length >= 5 && u16(d + 3) >= 0x0200) uac2 = true
                        2 -> if (uac2 && length >= 8) clocks[u8(d + 3)] = u8(d + 7) // input terminal: its clock
                        0x0A -> if (uac2 && length >= 6) { if (u8(d + 5) and 0x03 == 0x03) programmable.add(u8(d + 3)) } // clock source
                        6 -> if (length >= 7) {
                            val unit = u8(d + 3); val source = u8(d + 4)
                            fun control(channel: Int): Boolean = if (uac2) {
                                val o = d + 5 + channel * 4
                                o + 3 < d + length && (u8(o) and 0x0C) == 0x0C
                            } else {
                                val size = u8(d + 5)
                                val o = d + 6 + channel * size
                                size > 0 && o < d + length && (u8(o) and 0x02) != 0
                            }
                            featureSources.add(source to UsbVolumeUnit(unit, control(0), control(1), control(2)))
                        }
                    }
                    if (i != null && i[2] == AUDIO && i[3] == STREAMING) when (subtype) {
                        1 -> if (length >= 4) { link = u8(d + 3); if (uac2 && length >= 11) channels = u8(d + 10) }
                        2 -> if (uac2) { if (length >= 6) { subslot = u8(d + 4); bits = u8(d + 5) } }
                        else if (length >= 8 && u8(d + 3) == 1) {
                            channels = u8(d + 4); subslot = u8(d + 5); bits = u8(d + 6)
                            val count = u8(d + 7)
                            val list = ArrayList<Int>()
                            if (count == 0) { if (length >= 14) { list.add(u24(d + 8)); list.add(u24(d + 11)) } }
                            else for (k in 0 until count) if (d + 8 + k * 3 + 2 < d + length) list.add(u24(d + 8 + k * 3))
                            rates = list
                        }
                    }
                }
                ENDPOINT -> if (length >= 7) {
                    val i = current
                    if (i != null && i[2] == AUDIO && i[3] == STREAMING) {
                        val address = u8(d + 2); val attributes = u8(d + 3)
                        val raw16 = u16(d + 4)
                        val size = (raw16 and 0x7FF) * (1 + ((raw16 shr 11) and 0x3))
                        if (attributes and 0x03 == 1) {
                            val usage = (attributes shr 4) and 0x3
                            if ((address and 0x80) == 0 && usage == 0) { endpoint = address; maxPacket = size; interval = u8(d + 6).coerceAtLeast(1); sync = (attributes shr 2) and 0x3 }
                            else if ((address and 0x80) != 0 && usage == 1) { feedback = address; feedbackPacket = size }
                        }
                    }
                }
            }
            at += length
        }
        closeAlt()
        if (controlInterface < 0 || playback.isEmpty()) return null
        val volume = HashMap<Int, UsbVolumeUnit>()
        for ((source, unit) in featureSources) if (unit.master || unit.left) volume[source] = unit
        return UsbAudioInfo(controlInterface, uac2, playback, clocks, programmable, volume)
    }
}
