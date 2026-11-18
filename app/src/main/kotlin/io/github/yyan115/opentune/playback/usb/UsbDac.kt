package io.github.yyan115.opentune.playback.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.yyan115.opentune.core.usb.UsbAltSetting
import io.github.yyan115.opentune.core.usb.UsbAudioDescriptors
import io.github.yyan115.opentune.core.usb.UsbAudioInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The bridge to the native engine in app/src/main/cpp/usbaudio.c. */
object UsbNative {
    val loaded: Boolean = runCatching { System.loadLibrary("usbaudio") }.isSuccess

    @JvmStatic external fun start(fd: Int, endpoint: Int, maxPacket: Int, frameBytes: Int, rate: Int, packetsPerSecond: Int, feedback: Int, feedbackBytes: Int, highSpeed: Int, ringBytes: Int): Long
    @JvmStatic external fun write(handle: Long, buffer: java.nio.ByteBuffer, length: Int): Int
    @JvmStatic external fun framesPlayed(handle: Long): Long
    @JvmStatic external fun queuedBytes(handle: Long): Int
    @JvmStatic external fun underruns(handle: Long): Int
    @JvmStatic external fun running(handle: Long): Boolean
    @JvmStatic external fun setPaused(handle: Long, paused: Boolean)
    @JvmStatic external fun flush(handle: Long)
    @JvmStatic external fun stop(handle: Long)
    @JvmStatic external fun speed(fd: Int): Int
}

/** What the screen shows about the DAC. */
data class UsbDacState(
    val name: String? = null,
    /** The listener allowed the app to use the device. */
    val allowed: Boolean = false,
    /** What plays to it now: rate, bits and channels, or null when nothing does. */
    val playing: String? = null,
    val hardwareVolume: Boolean = false,
    val underruns: Int = 0,
)

/** A stream that is open to the DAC at one rate and one format. */
class UsbStream internal constructor(val handle: Long, val setting: UsbAltSetting, val rate: Int) {
    val frameBytes: Int get() = setting.frameBytes
    fun write(buffer: java.nio.ByteBuffer, length: Int): Int = UsbNative.write(handle, buffer, length)
    val framesPlayed: Long get() = UsbNative.framesPlayed(handle)
    val queuedBytes: Int get() = UsbNative.queuedBytes(handle)
    val running: Boolean get() = UsbNative.running(handle)
    fun paused(on: Boolean) = UsbNative.setPaused(handle, on)
    fun flush() = UsbNative.flush(handle)
}

/**
 * The app's own driver for a USB audio DAC (USB Audio Class 1 or 2), as the official TIDAL app has: with the listener's permission the app takes the DAC from
 * Android and streams the decoded audio to it at the song's own rate and bit depth, so nothing in Android resamples or mixes it. It works on every Android
 * version that the app runs on. Android's media volume keys move the DAC's own volume control, so the samples stay untouched.
 */
object UsbDac {
    private const val TAG = "OpenTuneUsb"
    private const val PERMISSION = "io.github.yyan115.opentune.USB_PERMISSION"
    private val RATES = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000)

    private lateinit var app: Context
    private val usb: UsbManager by lazy { app.getSystemService(Context.USB_SERVICE) as UsbManager }
    private val audio: AudioManager by lazy { app.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    /** The listener's setting: use the app's own driver when a DAC is plugged in. */
    @Volatile var enabled = false
        private set
    @Volatile private var device: UsbDevice? = null
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile var info: UsbAudioInfo? = null
        private set
    private var classTwoRates: Set<Int> = emptySet()
    private var claimed = ArrayList<UsbInterface>()
    @Volatile private var stream: UsbStream? = null
    // The DAC's volume range on its feature unit, in 1/256 dB, and the channels that have the control.
    private var volumeRange: IntArray? = null
    private var volumeChannels: List<Int> = emptyList()
    /** The gain that the sink applies itself when the DAC has no volume control: 1 keeps the samples bit-perfect. */
    @Volatile var softwareGain = 1f
        private set

    private val mutableState = MutableStateFlow(UsbDacState())
    val state: StateFlow<UsbDacState> = mutableState.asStateFlow()

    /** True when a DAC is open and the app may stream to it. */
    val ready: Boolean get() = enabled && connection != null && info != null

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        val filter = IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(PERMISSION) }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { applyVolume() }
        })
    }

    fun setEnabled(on: Boolean) {
        if (!::app.isInitialized || on == enabled) return
        enabled = on
        if (on) findDevice() else close()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> if (enabled) findDevice()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val gone = deviceOf(intent)
                    if (gone == null || gone.deviceName == device?.deviceName) close()
                }
                PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val target = deviceOf(intent) ?: device
                    Log.i(TAG, "USB permission for ${target?.productName}: $granted")
                    if (granted && target != null) open(target)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun isAudio(d: UsbDevice) = (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }

    /** Looks for a DAC among the USB devices, and asks the listener for it when the app may not use it yet. */
    private fun findDevice() {
        val found = usb.deviceList.values.firstOrNull(::isAudio) ?: run { mutableState.value = UsbDacState(); return }
        device = found
        mutableState.value = UsbDacState(found.productName ?: found.deviceName, usb.hasPermission(found))
        if (usb.hasPermission(found)) open(found)
        else {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(found, PendingIntent.getBroadcast(app, 0, Intent(PERMISSION).setPackage(app.packageName), flags))
        }
    }

    private fun open(target: UsbDevice) {
        if (connection != null && device?.deviceName == target.deviceName) return
        close()
        val c = usb.openDevice(target) ?: run { Log.w(TAG, "could not open ${target.productName}"); return }
        val raw = c.rawDescriptors
        Log.i(TAG, "descriptors of ${target.productName} (%04x:%04x): %s".format(target.vendorId, target.productId, raw.joinToString("") { "%02x".format(it) }))
        val parsed = UsbAudioDescriptors.parse(raw)
        if (parsed == null) { Log.w(TAG, "no playback interface on ${target.productName}"); c.close(); return }
        device = target; connection = c; info = parsed
        // The control interface is taken from Android's own driver, so that Android stops using the DAC and the app can set its rate and volume.
        interfaceOf(target, parsed.controlInterface, 0)?.let { if (c.claimInterface(it, true)) claimed.add(it) }
        classTwoRates = if (parsed.uac2) classTwoRatesOf(c, parsed) else emptySet()
        Log.i(TAG, "${target.productName}: class ${if (parsed.uac2) 2 else 1}, settings ${parsed.playback.joinToString { "${it.channels}ch ${it.bits}bit/${it.subslotBytes}B rates ${it.rates ?: classTwoRates}" }}")
        setUpVolume(c, parsed)
        mutableState.value = UsbDacState(target.productName ?: target.deviceName, true, null, volumeRange != null)
    }

    private fun interfaceOf(d: UsbDevice, number: Int, alternate: Int): UsbInterface? =
        (0 until d.interfaceCount).map(d::getInterface).firstOrNull { it.id == number && it.alternateSetting == alternate }

    /** The rates of a class 2 DAC: the ranges its clocks report. */
    private fun classTwoRatesOf(c: UsbDeviceConnection, i: UsbAudioInfo): Set<Int> {
        val out = HashSet<Int>()
        for (clock in i.clocks.values.toSet()) {
            val buffer = ByteArray(2 + 12 * 16)
            val n = c.controlTransfer(0xA1, 0x02, 0x0100, (clock shl 8) or i.controlInterface, buffer, buffer.size, 1000)
            if (n < 2) continue
            val count = (buffer[0].toInt() and 0xFF) or ((buffer[1].toInt() and 0xFF) shl 8)
            for (k in 0 until count) {
                val o = 2 + k * 12
                if (o + 8 > n) break
                fun u32(at: Int) = (buffer[at].toInt() and 0xFF) or ((buffer[at + 1].toInt() and 0xFF) shl 8) or ((buffer[at + 2].toInt() and 0xFF) shl 16) or ((buffer[at + 3].toInt() and 0xFF) shl 24)
                val min = u32(o); val max = u32(o + 4)
                if (min == max) out.add(min) else RATES.filter { it in min..max }.forEach(out::add)
            }
        }
        return out
    }

    // ---- volume ----

    private fun setUpVolume(c: UsbDeviceConnection, i: UsbAudioInfo) {
        volumeRange = null; volumeChannels = emptyList(); softwareGain = 1f
        val unit = i.playback.firstNotNullOfOrNull { i.volume[it.terminalLink] } ?: run { applyVolume(); return }
        val channels = if (unit.master) listOf(0) else listOfNotNull(if (unit.left) 1 else null, if (unit.right) 2 else null)
        val index = (unit.unitId shl 8) or i.controlInterface
        val range: IntArray? = if (i.uac2) {
            val b = ByteArray(8)
            if (c.controlTransfer(0xA1, 0x02, 0x0200 or channels.first(), index, b, b.size, 1000) >= 6) intArrayOf(s16(b, 2), s16(b, 4)) else null
        } else {
            val min = ByteArray(2); val max = ByteArray(2)
            if (c.controlTransfer(0xA1, 0x82, 0x0200 or channels.first(), index, min, 2, 1000) == 2 && c.controlTransfer(0xA1, 0x83, 0x0200 or channels.first(), index, max, 2, 1000) == 2)
                intArrayOf(s16(min, 0), s16(max, 0)) else null
        }
        if (range != null && range[1] > range[0]) { volumeRange = range; volumeChannels = channels; volumeUnit = index }
        Log.i(TAG, "volume: unit ${unit.unitId}, channels $channels, range ${range?.let { "%.1f to %.1f dB".format(it[0] / 256.0, it[1] / 256.0) } ?: "none"}")
        applyVolume()
    }

    private var volumeUnit = 0
    private fun s16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) or (b[at + 1].toInt() shl 8)).toShort().toInt()

    /**
     * Follows Android's media volume: the steps of the volume keys are spread over the 60 dB below the DAC's maximum, and the lowest step is silence.
     * Without a volume control on the DAC, the sink scales the samples instead (and is then bit-perfect only at full volume).
     */
    fun applyVolume() {
        if (!::app.isInitialized) return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val index = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val fraction = index.toDouble() / max
        val range = volumeRange
        val c = connection
        if (range == null || c == null) { softwareGain = if (index == 0) 0f else Math.pow(10.0, 60.0 * (fraction - 1) / 20).toFloat(); return }
        softwareGain = 1f
        val db = if (index == 0) range[0] else (range[1] + (fraction - 1) * 60 * 256).toInt().coerceIn(range[0], range[1])
        val value = byteArrayOf((db and 0xFF).toByte(), ((db shr 8) and 0xFF).toByte())
        for (channel in volumeChannels) c.controlTransfer(0x21, 0x01, 0x0200 or channel, volumeUnit, value, 2, 1000)
    }

    // ---- streams ----

    /** Whether the DAC can take [rate] at [channels] as it is. */
    fun canPlay(rate: Int, channels: Int): Boolean = ready && info?.settingsFor(rate, channels, classTwoRates)?.isNotEmpty() == true

    /** Opens a stream at [rate] and [channels] in the deepest format that the DAC has for it, or returns null. Any stream that was open is closed first. */
    @Synchronized
    fun openStream(rate: Int, channels: Int): UsbStream? {
        closeStream()
        if (!UsbNative.loaded) return null
        val i = info ?: return null
        val c = connection ?: return null
        val d = device ?: return null
        val setting = i.settingsFor(rate, channels, classTwoRates).firstOrNull() ?: return null
        val intf = interfaceOf(d, setting.interfaceNumber, setting.alternate) ?: return null
        if (!c.claimInterface(intf, true)) { Log.w(TAG, "could not claim the streaming interface"); return null }
        claimed.add(intf)
        if (!c.setInterface(intf)) { Log.w(TAG, "could not select alternate ${setting.alternate}"); return null }
        // The rate: on the endpoint for class 1, on the clock of the terminal for class 2.
        if (i.uac2) {
            val clock = i.clocks[setting.terminalLink] ?: i.clocks.values.firstOrNull()
            if (clock != null) c.controlTransfer(0x21, 0x01, 0x0100, (clock shl 8) or i.controlInterface, le(rate, 4), 4, 1000)
        } else c.controlTransfer(0x22, 0x01, 0x0100, setting.endpoint, le(rate, 3), 3, 1000)
        val speed = UsbNative.speed(c.fileDescriptor)
        val high = speed >= 3
        val packetsPerSecond = (if (high) 8000 else 1000) / (1 shl (setting.interval - 1).coerceIn(0, 4))
        val handle = UsbNative.start(c.fileDescriptor, setting.endpoint, setting.maxPacket, setting.frameBytes, rate, packetsPerSecond,
            setting.feedbackEndpoint ?: 0, if (high) 4 else 3, if (high) 1 else 0, rate * setting.frameBytes / 4)
        if (handle == 0L) return null
        applyVolume()
        Log.i(TAG, "streaming $rate Hz, ${setting.bits} bits in ${setting.subslotBytes} bytes, ${setting.channels} channels, speed $speed, ${packetsPerSecond} packets/s")
        mutableState.value = mutableState.value.copy(playing = "%.1f kHz · %d-bit".format(rate / 1000.0, setting.bits))
        return UsbStream(handle, setting, rate).also { stream = it }
    }

    @Synchronized
    fun closeStream(which: UsbStream? = null) {
        val s = stream ?: return
        if (which != null && which !== s) return
        stream = null
        mutableState.value = mutableState.value.copy(playing = null, underruns = UsbNative.underruns(s.handle))
        UsbNative.stop(s.handle)
        // The zero-bandwidth setting frees the bus.
        val d = device; val c = connection
        if (d != null && c != null) interfaceOf(d, s.setting.interfaceNumber, 0)?.let { c.setInterface(it) }
    }

    private fun le(value: Int, bytes: Int) = ByteArray(bytes) { ((value shr (8 * it)) and 0xFF).toByte() }

    @Synchronized
    private fun close() {
        closeStream()
        val c = connection
        if (c != null) { for (i in claimed) runCatching { c.releaseInterface(i) }; c.close() }
        claimed.clear(); connection = null; info = null; volumeRange = null; softwareGain = 1f
        mutableState.value = UsbDacState(device?.productName, false)
        device = null
    }
}
