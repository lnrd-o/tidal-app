package io.github.lnrdo.mytidal.core.dj.neural

import io.github.lnrdo.mytidal.core.dj.MusicGenre
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YamnetGenresTest {
    private val ref = File("../app/src/androidTest/assets/yamnet-ref")
    private val assets = File("../app/src/main/assets/yamnet")

    private fun floats(file: File): FloatArray {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    @Test fun theFeaturesAreTheOnesThatTensorFlowMakes() {
        val features = YamnetFeatures(floats(File(assets, "yamnet_mel_257x64.bin")))
        val wave = floats(File(ref, "yamnet_wave16k.f32"))
        val expected = floats(File(ref, "yamnet_logmel.f32"))
        val (logMel, frames) = features.logMel(wave)
        assertEquals(expected.size / 64, frames)
        var worst = 0f
        for (i in expected.indices) worst = maxOf(worst, abs(logMel[i] - expected[i]))
        println("largest difference from TensorFlow: $worst")
        assertTrue("within 1e-3 of TensorFlow: $worst", worst < 1e-3f)
        // The patches are cut as YAMNet cuts them.
        val patches = floats(File(ref, "yamnet_patches.f32"))
        assertEquals(patches.size / (96 * 64), features.patchCount(frames))
        val first = features.patch(logMel, 1)
        for (i in 0 until 96 * 64) assertEquals(patches[96 * 64 + i], first[i], 1e-3f)
    }

    @Test fun aShortSongHasNoPatchesAndAnEmptyOneNoFrames() {
        val features = YamnetFeatures(FloatArray(257 * 64))
        assertEquals(0, features.logMel(FloatArray(100)).second)
        assertEquals(0, features.patchCount(95))
        assertEquals(1, features.patchCount(96)); assertEquals(2, features.patchCount(144))
    }

    @Test fun theClassesAreNamedByTheMapOfTheNetwork() {
        val names = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(File(assets, "yamnet_classes.json").readText()).map { it.groupValues[1] }.toList()
        assertEquals(521, names.size)
        val table = YamnetGenres.table(names)
        assertEquals(MusicGenre.HIPHOP, table[names.indexOf("Hip hop music")])
        assertEquals(MusicGenre.DNB, table[names.indexOf("Drum and bass")])
        assertEquals(MusicGenre.TROPICAL, table[names.indexOf("Salsa music")])
        assertNull("speech is not a style", table[names.indexOf("Speech")])
        // Every style class that was meant is in the map of the network: none of the names was misspelt.
        assertEquals(40, table.count { it != null })
        assertEquals(MusicGenre.METAL, table[names.indexOf("Heavy metal")])
        assertEquals(MusicGenre.COUNTRY, table[names.indexOf("Country")])
        assertEquals(MusicGenre.JAZZ, table[names.indexOf("Jazz")])
    }

    private fun mean(vararg pairs: Pair<Int, Float>) = FloatArray(521).also { for ((i, v) in pairs) it[i] = v }

    @Test fun theBestFamilyWinsWhenItIsClearAndNothingWinsWhenItIsNot() {
        val names = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(File(assets, "yamnet_classes.json").readText()).map { it.groupValues[1] }.toList()
        val table = YamnetGenres.table(names)
        fun at(n: String) = names.indexOf(n)
        val rap = YamnetGenres.decide(mean(at("Hip hop music") to 0.5f, at("Pop music") to 0.2f), table)
        assertEquals(MusicGenre.HIPHOP, rap.genre); assertTrue(rap.confidence > 0.4f)
        // A family with many classes does not win just by having many: rock's best class is 0.1, pop's is 0.15.
        val v = YamnetGenres.decide(mean(at("Rock music") to 0.1f, at("Heavy metal") to 0.09f, at("Punk rock") to 0.09f, at("Pop music") to 0.15f), table)
        assertEquals(MusicGenre.POP, v.genre)
        assertNull("too quiet", YamnetGenres.decide(mean(at("Pop music") to 0.02f), table).genre)
        assertNull("too close", YamnetGenres.decide(mean(at("Pop music") to 0.30f, at("Rock music") to 0.295f), table).genre)
        assertNull(YamnetGenres.decide(FloatArray(521), table).genre)
    }
}
