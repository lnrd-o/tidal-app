package io.github.lnrdo.mytidal.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.lnrdo.mytidal.R
import io.github.lnrdo.mytidal.playback.dj.DjMix
import io.github.lnrdo.mytidal.playback.dj.neural.Accelerator
import io.github.lnrdo.mytidal.playback.dj.neural.BeatModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What one thread of the app took of the processor over the last second, in percent of one core. */
class ThreadLoad(val name: String, val percent: Double)

/** The load of the app over the last second, for the debugging overlay. */
class AppLoad(
    /** Percent of one core, so that 100 is one core fully used and 800 is eight. */
    val cores: Double,
    val coresCount: Int,
    val threads: List<ThreadLoad>,
    /** The load of the graphics processor of the device in percent, when the system lets an app read it. */
    val gpuSystem: Int?,
)

/** Reads the time that the kernel counted for each thread of this process. */
object LoadSampler {
    private var last = HashMap<Int, Long>()
    private var lastAt = 0L

    fun sample(): AppLoad? = runCatching {
        val now = SystemClock.elapsedRealtime()
        val ticks = HashMap<Int, Long>(); val names = HashMap<Int, String>()
        File("/proc/self/task").listFiles()?.forEach { dir ->
            val tid = dir.name.toIntOrNull() ?: return@forEach
            runCatching {
                val text = File(dir, "stat").readText()
                val name = text.substringAfter('(').substringBeforeLast(')')
                val fields = text.substringAfterLast(')').trim().split(' ')
                ticks[tid] = fields[11].toLong() + fields[12].toLong()
                names[tid] = name
            }
        }
        val previous = last; val then = lastAt
        last = ticks; lastAt = now
        if (then == 0L || now <= then) return@runCatching null
        val seconds = (now - then) / 1000.0
        // A tick is 10 ms. The threads of a pool share a name, so their load is added by name.
        val byName = HashMap<String, Double>()
        var total = 0.0
        for ((tid, t) in ticks) {
            val delta = (t - (previous[tid] ?: t)).coerceAtLeast(0)
            val percent = delta * 10.0 / 1000.0 / seconds * 100.0
            total += percent
            val key = names[tid]!!.replace(Regex("[-_ ]?\\d+$"), "")
            byName[key] = (byName[key] ?: 0.0) + percent
        }
        val top = byName.entries.sortedByDescending { it.value }.take(4).filter { it.value >= 1.0 }.map { ThreadLoad(it.key, it.value) }
        AppLoad(total, Runtime.getRuntime().availableProcessors(), top, gpuSystem())
    }.getOrNull()

    /** Qualcomm's graphics driver publishes how busy the GPU is; most phones do not let an app read it, and then this is null. */
    private fun gpuSystem(): Int? = listOf("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load")
        .firstNotNullOfOrNull { path -> runCatching { File(path).readText().trim().removeSuffix("%").trim().toInt() }.getOrNull() }
}

/**
 * A small panel for debugging, at the top of the cover: what the app takes of the CPU (in cores, with the threads that take most), how long the rhythm model
 * kept the NPU or the GPU busy over the last minute, and what the graphics processor of the device is doing when the system lets the app know.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun LoadOverlay(modifier: Modifier = Modifier) {
    var load by remember { mutableStateOf<AppLoad?>(null) }
    LaunchedEffect(Unit) { while (true) { load = withContext(Dispatchers.IO) { LoadSampler.sample() } ?: load; delay(1_000) } }
    val model by BeatModel.state.collectAsStateWithLifecycle()
    val genre by io.github.lnrdo.mytidal.playback.dj.neural.GenreModel.state.collectAsStateWithLifecycle()
    val genreOnGpu = genre.loaded == Accelerator.GPU
    val l = load ?: return
    val reading = (DjMix.ANALYSIS_SLOT..DjMix.SCOUT_SLOT).count { DjMix.streams[it].ring != null }
    val where = model.loaded
    Column(modifier.padding(6.dp).background(Color.Black.copy(alpha = .66f)).padding(horizontal = 8.dp, vertical = 5.dp)) {
        val mono = AppFonts.mono
        Text(stringResource(R.string.load_cpu, "%.0f".format(l.cores), "%.1f".format(l.cores / 100.0), l.coresCount), fontFamily = mono, fontSize = 10.sp, color = if (l.cores / 100.0 > l.coresCount * .35) Color(0xFFFFB454) else Color.White)
        for (t in l.threads) Text("  %s %.0f%%".format(t.name.take(24), t.percent), fontFamily = mono, fontSize = 9.sp, color = Color.White.copy(alpha = .7f))
        Text(stringResource(R.string.load_npu, if (where == Accelerator.NPU) "%.0f".format(model.busyPercent) else "—"), fontFamily = mono, fontSize = 10.sp, color = if (where == Accelerator.NPU) Color(0xFFD6B56A) else Color.White.copy(alpha = .5f))
        Text(stringResource(R.string.load_gpu, if (genreOnGpu) "%.0f".format(genre.busyPercent) else "—", l.gpuSystem?.let { "$it%" } ?: "—"), fontFamily = mono, fontSize = 10.sp,
            color = if (genreOnGpu) Color(0xFF3B82F6) else Color.White.copy(alpha = .5f))
        Text(stringResource(R.string.load_reading, reading, where?.name ?: "—"), fontFamily = mono, fontSize = 9.sp, color = Color.White.copy(alpha = .6f))
    }
}
