package io.github.yyan115.opentune.ui

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.SystemClock
import java.io.File

/** The processors of the device, as D'JAI shows them. Each field is null when it could not be read. */
class DeviceHardware(
    /** The maker and the model of the chip, for example "QTI SM8650". */
    val chip: String?,
    val cores: Int,
    /** The clusters of cores, fastest first, for example "1 × 3.39 GHz, 5 × 3.15 GHz, 2 × 2.27 GHz". */
    val clusters: String?,
    /** The graphics processor, as its driver names it, for example "Adreno (TM) 750". */
    val gpu: String?,
    val vulkan: Boolean,
    /** The neural processor that the chip carries, by its maker, or null when none is known for this chip. */
    val npu: String?,
)

object HardwareInfo {
    @Volatile private var cached: DeviceHardware? = null

    /** Reads the hardware once. It opens a small graphics context to ask the driver its name, so it is called off the main thread. */
    fun read(context: Context): DeviceHardware = cached ?: DeviceHardware(chip(), Runtime.getRuntime().availableProcessors(), clusters(), gpuName(),
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL), npu()).also { cached = it }

    private fun chip(): String? =
        if (Build.VERSION.SDK_INT >= 31) listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL).filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" ").ifBlank { null }
        else Build.HARDWARE.ifBlank { null }

    private fun clusters(): String? = runCatching {
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { File("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq").takeIf(File::canRead)?.readText()?.trim()?.toLongOrNull() }
        if (freqs.isEmpty()) null
        else freqs.groupingBy { it }.eachCount().toSortedMap(compareByDescending { it }).entries.joinToString(", ") { (khz, n) -> "%d × %.2f GHz".format(n, khz / 1e6) }
    }.getOrNull()

    private fun gpuName(): String? = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
        try {
            val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
            val attributes = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
            if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
            val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            val surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            try {
                EGL14.eglMakeCurrent(display, surface, surface, context)
                GLES20.glGetString(GLES20.GL_RENDERER)?.trim()?.ifBlank { null }
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context)
            }
        } finally { EGL14.eglTerminate(display) }
    }.getOrNull()

    /**
     * The neural processor of the chip, from its model: Android has no public way to list it, so it is known from the families of chips that carry one.
     * Null when the chip is not one of them.
     */
    private fun npu(): String? {
        val model = (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE).uppercase()
        val maker = (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else Build.MANUFACTURER).uppercase()
        return when {
            maker.contains("QTI") || maker.contains("QUALCOMM") || model.startsWith("SM") || model.startsWith("QCS") ->
                if (Regex("SM8[4-9]\\d\\d").containsMatchIn(model)) "Qualcomm Hexagon NPU" else "Qualcomm Hexagon DSP"
            maker.contains("GOOGLE") || model.contains("TENSOR") || model.startsWith("GS") -> "Google TPU"
            maker.contains("MEDIATEK") || model.startsWith("MT") -> "MediaTek APU"
            maker.contains("SAMSUNG") || model.startsWith("S5E") || model.contains("EXYNOS") -> "Samsung NPU"
            model.contains("KIRIN") -> "Huawei Da Vinci NPU"
            else -> null
        }
    }

    // ---- what the app takes of the processor now ----

    private var lastTicks = -1L
    private var lastAt = 0L

    /**
     * The share of the whole processor that the app used since the last call, from 0 to 100, or null on the first call or when it cannot be read.
     * It reads the time that the kernel counted for this process.
     */
    fun cpuPercent(): Double? = runCatching {
        val fields = File("/proc/self/stat").readText().substringAfterLast(')').trim().split(' ')
        // After the name: state is field 3, so utime (14) and stime (15) are at 11 and 12 here.
        val ticks = fields[11].toLong() + fields[12].toLong()
        val now = SystemClock.elapsedRealtime()
        val previous = lastTicks; val then = lastAt
        lastTicks = ticks; lastAt = now
        if (previous < 0 || now <= then) null
        else ((ticks - previous) * 10.0 / (now - then) * 100.0 / Runtime.getRuntime().availableProcessors()).coerceIn(0.0, 100.0)
    }.getOrNull()
}
