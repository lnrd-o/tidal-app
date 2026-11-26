package io.github.lnrdo.mytidal

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lnrdo.mytidal.core.stems.Stem
import io.github.lnrdo.mytidal.core.stems.StemSeparator
import io.github.lnrdo.mytidal.core.stems.StemSink
import io.github.lnrdo.mytidal.playback.dj.neural.Accelerator
import io.github.lnrdo.mytidal.stems.SongCapture
import io.github.lnrdo.mytidal.stems.StemModel
import io.github.lnrdo.mytidal.stems.StemNetworks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Finds a song on TIDAL ("query" argument), captures it once and separates it with several settings, logging how the energy is shared between the stems. */
@RunWith(AndroidJUnit4::class)
class StemCompareTest {
    private class Meter : StemSink {
        val energy = DoubleArray(4)
        override fun write(stem: Int, channel: Int, offset: Int, samples: FloatArray, count: Int) { var s = 0.0; for (i in 0 until count) s += samples[i].toDouble() * samples[i]; energy[stem] += s }
    }

    @Test fun compare() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenTuneApp
        val query = InstrumentationRegistry.getArguments().getString("query") ?: "Psychosocial Slipknot"
        val track = app.tidal.search(query).tracks.first()
        Log.i("StemCompare", "song: ${track.title} (${track.durationMs / 1000} s)")
        val song = SongCapture.read(app, track, {}, { false })
        assertTrue("capture failed", song != null)
        var mix = 0.0
        run { val l = FloatArray(4096); val r = FloatArray(4096); var o = 0
            while (o < song!!.length) { val n = minOf(4096, song.length - o); song.read(o, n, l, r); for (i in 0 until n) mix += l[i].toDouble() * l[i] + r[i].toDouble() * r[i]; o += n } }
        Log.i("StemCompare", "mix energy $mix")
        for ((model, power) in if (InstrumentationRegistry.getArguments().getString("only") == "scnet") emptyList() else listOf(StemModel.SPLEETER to 2, StemModel.BOTH to 2)) {
            val network = StemNetworks.open(app, model, if (model == StemModel.OPEN_UNMIX) Accelerator.CPU else Accelerator.NPU) ?: continue
            val meter = Meter()
            val began = System.currentTimeMillis()
            network.use { StemSeparator(network, power = power).separate(song!!, meter) }
            val shares = Stem.entries.indices.joinToString("  ") { "${Stem.entries[it].id} ${"%.1f".format(100 * meter.energy[it] / meter.energy.sum())}%" }
            Log.i("StemCompare", "${model.id} p$power (${(System.currentTimeMillis() - began) / 1000} s): $shares")
        }
        StemNetworks.openScnet(app)?.let { net ->
            val meter = Meter()
            val began = System.currentTimeMillis()
            net.use { io.github.lnrdo.mytidal.core.stems.ScnetSeparator(net).separate(song!!, meter, { Log.i("StemCompare", "scnet ${(it * 100).toInt()}% at ${(System.currentTimeMillis() - began) / 1000} s") }) }
            val shares = Stem.entries.indices.joinToString("  ") { "${Stem.entries[it].id} ${"%.1f".format(100 * meter.energy[it] / meter.energy.sum())}%" }
            Log.i("StemCompare", "scnet (${(System.currentTimeMillis() - began) / 1000} s): $shares, stems/mix ${"%.2f".format(meter.energy.sum() / mix)}")
        }
        StemNetworks.closeIfIdle(0)
    }
}
