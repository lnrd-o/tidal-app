package io.github.lnrdo.mytidal.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat as ProcessorFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import io.github.lnrdo.mytidal.core.spatial.SpatialMode
import io.github.lnrdo.mytidal.core.spatial.Upmixer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the device can do with surround sound, and where the sound goes now. */
data class SpatialReport(
    /** The system has a spatializer (Android 12L and later) and it can place surround channels around the listener. */
    val available: Boolean,
    /** The user has it on in the system sound settings. */
    val enabled: Boolean,
    /** The channels of the biggest layout that the system will spatialize on the present output: 0, 6 (5.1) or 8 (7.1). */
    val channels: Int,
    val output: Output,
    /** The device has the Dolby audio service. */
    val dolby: Boolean,
) {
    enum class Output { SPEAKER, HEADPHONES, OTHER }
    /** Whether the upmix would be spatialized, and so worth using, on the present output. */
    val usable get() = available && enabled && channels > 0
}

object SpatialSupport {
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    fun report(context: Context): SpatialReport {
        val audio = context.getSystemService(AudioManager::class.java)
        val output = output(audio)
        val dolby = runCatching { context.packageManager.getPackageInfo("com.dolby.daxservice", 0); true }.getOrDefault(false)
        if (Build.VERSION.SDK_INT < 32) return SpatialReport(false, false, 0, output, dolby)
        val spatializer = audio.spatializer
        fun can(mask: Int) = runCatching {
            spatializer.canBeSpatialized(attributes, AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(48_000).setChannelMask(mask).build())
        }.getOrDefault(false)
        val channels = if (can(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND)) 8 else if (can(AudioFormat.CHANNEL_OUT_5POINT1)) 6 else 0
        return SpatialReport(spatializer.isAvailable, spatializer.isEnabled, channels, output, dolby)
    }

    private fun output(audio: AudioManager): SpatialReport.Output {
        val routed: List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= 33) audio.getAudioDevicesForAttributes(attributes) else emptyList()
        // When the system names no route (nothing plays), the connected outputs say where the sound would go: headphones win over the speaker.
        val devices = routed.ifEmpty { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
        val types = devices.map { it.type }
        val headphones = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_BROADCAST)
        return when {
            types.any { it in headphones } -> SpatialReport.Output.HEADPHONES
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in types -> SpatialReport.Output.SPEAKER
            else -> SpatialReport.Output.OTHER
        }
    }
}

/** What the upmix is doing now, shared by the players, the mixer's output and the screen that shows it. */
object SpatialState {
    /** The mode that is on, or null when the upmix is off. */
    @Volatile var mode: SpatialMode? = null
    /** The channels of the layout that the upmix makes (6 or 8), or 0 when it does not run. A change takes effect when a song's audio is next configured. */
    @Volatile var layout = 0
    /** The upmixer of the song that is heard, whose levels the screen shows. */
    @Volatile var upmixer: Upmixer? = null

    fun levels(): FloatArray? = upmixer?.levels
}

/**
 * Turns the stereo that is about to be played into 5.1 or 7.1 for the system's spatializer, with [Upmixer]. When the upmix is off or the audio is not stereo
 * it is inactive and the audio passes unchanged. It sits after the taps of D'JAI and the spectrum, which keep seeing stereo.
 */
@UnstableApi
class SpatialProcessor(private val slot: Int) : BaseAudioProcessor() {
    private var upmixer: Upmixer? = null
    private var encoding = C.ENCODING_PCM_FLOAT
    private var input = FloatArray(0)
    private var output = FloatArray(0)

    override fun onConfigure(inputAudioFormat: ProcessorFormat): ProcessorFormat {
        val layout = SpatialState.layout
        if (layout == 0 || SpatialState.mode == null || inputAudioFormat.channelCount != 2 || inputAudioFormat.encoding !in SUPPORTED) { upmixer = null; return ProcessorFormat.NOT_SET }
        encoding = inputAudioFormat.encoding
        upmixer = Upmixer(inputAudioFormat.sampleRate, layout).also { if (slot == io.github.lnrdo.mytidal.playback.dj.DjMix.sessionSlot) SpatialState.upmixer = it }
        return ProcessorFormat(inputAudioFormat.sampleRate, layout, C.ENCODING_PCM_FLOAT)
    }

    override fun onFlush(streamMetadata: androidx.media3.common.audio.AudioProcessor.StreamMetadata) { upmixer?.reset() }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val up = upmixer ?: return
        val bytes = if (encoding == C.ENCODING_PCM_16BIT) 2 else 4
        val frames = inputBuffer.remaining() / (2 * bytes)
        if (frames == 0) return
        if (input.size < frames * 2) input = FloatArray(frames * 2)
        if (output.size < frames * up.channels) output = FloatArray(frames * up.channels)
        val buffer = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        val base = buffer.position()
        for (i in 0 until frames * 2) input[i] = if (encoding == C.ENCODING_PCM_16BIT) buffer.getShort(base + 2 * i) * (1f / 32768f) else buffer.getFloat(base + 4 * i)
        inputBuffer.position(inputBuffer.position() + frames * 2 * bytes)
        SpatialState.mode?.let { up.mode = it }
        up.process(input, 0, frames, output, 0)
        val out = replaceOutputBuffer(frames * up.channels * 4)
        out.asFloatBuffer().put(output, 0, frames * up.channels)
        out.position(out.position() + frames * up.channels * 4)
        out.flip()
    }

    override fun onReset() { upmixer = null }

    private companion object { val SUPPORTED = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT) }
}
