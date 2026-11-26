package io.github.lnrdo.mytidal

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.lnrdo.mytidal.stems.StemModel
import io.github.lnrdo.mytidal.stems.StemProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Separates the song that was heard last through the real path (the hidden reader, the network, the store) and logs every step. The phone must be signed in. */
@RunWith(AndroidJUnit4::class)
class StemManagerTest {
    @Test fun separatesTheLastSong() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenTuneApp
        val track = app.library.recent().firstOrNull { !it.isFile } ?: error("no song in the history")
        val model = StemModel.of(InstrumentationRegistry.getArguments().getString("model"))
        Log.i("StemManagerTest", "separating ${track.title} (${track.id}, ${track.durationMs / 1000} s) with ${model.id}")
        val began = System.currentTimeMillis()
        app.stems.separate(track, model)
        var last = ""
        while (true) {
            val p = app.stems.state.value[track.id]
            val line = "${p?.phase} ${"%.0f".format((p?.progress ?: 0f) * 100)}% on ${p?.ranOn}"
            if (line != last) { Log.i("StemManagerTest", "${(System.currentTimeMillis() - began) / 1000} s: $line"); last = line }
            if (p?.phase == StemProgress.Phase.DONE || p?.phase == StemProgress.Phase.FAILED || (p == null && System.currentTimeMillis() - began > 5_000)) break
            if (System.currentTimeMillis() - began > 600_000) break
            Thread.sleep(500)
        }
        val done = app.stems.state.value[track.id]?.phase == StemProgress.Phase.DONE
        Log.i("StemManagerTest", "done=$done, stored length ${app.stems.store.length(track.id)} samples")
        assertTrue(done)
        // Every stem must be on disk, whole: the record of a finished song says nothing about the files.
        assertTrue("some stem files are missing or short", app.stems.store.has(track.id))
        Log.i("StemManagerTest", "kept: ${app.stems.store.list().firstOrNull()?.let { "${it.track?.title} by ${it.track?.artist}, ${it.model}, ${it.bytes / 1_000_000} MB" }}")
        Unit
    }
}
