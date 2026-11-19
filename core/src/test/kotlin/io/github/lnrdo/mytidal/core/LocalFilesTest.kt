package io.github.lnrdo.mytidal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFilesTest {
    private val direct = "content://com.android.externalstorage.documents/document/primary%3AMusic%2FAlbums%2FNight%20Walk.flac"
    private val viaTree = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FAlbums%2FNight%20Walk.flac"

    @Test fun theSameFileHasOneKeyWhateverTheRoute() {
        assertEquals(DocumentKey.of(direct), DocumentKey.of(viaTree))
        assertEquals("com.android.externalstorage.documents|primary:Music/Albums/Night Walk.flac", DocumentKey.of(direct))
    }

    @Test fun differentFilesHaveDifferentKeys() {
        assertFalse(DocumentKey.of(direct) == DocumentKey.of(direct.replace("Night%20Walk", "Glass%20Rooms")))
        assertFalse(DocumentKey.of(direct) == DocumentKey.of(direct.replace("com.android.externalstorage.documents", "com.example.provider")))
    }

    @Test fun anAddressThatIsNotADocumentKeepsItsText() {
        assertEquals("file:///data/user/0/app/files/a.flac", DocumentKey.of("file:///data/user/0/app/files/a.flac"))
    }

    @Test fun theLocationSplitsTheVolumeAndThePath() {
        val location = DocumentKey.location(viaTree)!!
        assertEquals("primary", location.volume)
        assertEquals(listOf("Music", "Albums"), location.folders)
        assertEquals("Night Walk.flac", location.fileName)
        assertNull(DocumentKey.location("content://media/external/audio/media/42"))
    }

    @Test fun aPlusSignInAFileNameStaysAPlusSign() {
        assertEquals("a+b.flac", DocumentKey.location("content://x.documents/document/primary%3AMusic%2Fa+b.flac")!!.fileName)
    }

    private fun flacHead(rate: Int, bits: Int, channels: Int = 2): ByteArray {
        val head = ByteArray(42)
        "fLaC".toByteArray(Charsets.ISO_8859_1).copyInto(head)
        head[4] = 0; head[7] = 34
        head[18] = (rate ushr 12).toByte()
        head[19] = (rate ushr 4).toByte()
        head[20] = (((rate and 0xF) shl 4) or ((channels - 1) shl 1) or ((bits - 1) ushr 4)).toByte()
        head[21] = (((bits - 1) and 0xF) shl 4).toByte()
        return head
    }

    @Test fun aFlacHeaderGivesTheBitDepthAndTheSampleRate() {
        val info = AudioHeaders.flac(flacHead(96_000, 24))!!
        assertEquals(24, info.bitDepth); assertEquals(96_000, info.sampleRate)
        assertEquals("FLAC · 24-bit · 96.0 kHz", info.label)
        assertEquals("FLAC · 16-bit · 44.1 kHz", AudioHeaders.flac(flacHead(44_100, 16))!!.label)
        assertEquals(192_000, AudioHeaders.flac(flacHead(192_000, 24))!!.sampleRate)
    }

    @Test fun somethingThatIsNotFlacGivesNothing() {
        assertNull(AudioHeaders.flac(ByteArray(64)))
        assertNull(AudioHeaders.flac("fLaC".toByteArray()))
    }

    @Test fun aWavHeaderGivesTheFormat() {
        val head = ByteArray(44)
        "RIFF".toByteArray().copyInto(head); "WAVE".toByteArray().copyInto(head, 8); "fmt ".toByteArray().copyInto(head, 12)
        head[16] = 16
        val rate = 48_000
        head[24] = rate.toByte(); head[25] = (rate ushr 8).toByte(); head[26] = (rate ushr 16).toByte()
        head[34] = 24
        val info = AudioHeaders.wav(head)!!
        assertEquals("WAV · 24-bit · 48.0 kHz", info.label)
    }

    @Test fun theLabelLeavesOutWhatIsUnknown() {
        assertEquals("MP3 · 44.1 kHz · 320 kbps", AudioFormatInfo("MP3", null, 44_100, 320).label)
        assertEquals("OPUS", AudioFormatInfo("OPUS").label)
    }

    @Test fun filesAndDevicePlaylistsShareThePrefixAndTidalIdsDoNot() {
        assertTrue(isDeviceId("local:3f2a"))
        assertFalse(isDeviceId("123456789"))
        assertTrue(Track("local:1", "a", "b", localUri = "content://x").isFile)
        assertTrue(Track("1", "a", "b", localUri = "opentune-offline://1").isDownload)
        assertFalse(Track("1", "a", "b").onDevice)
    }
}

class RepeatPolicyTest {
    private val ids = listOf("a", "b", "c", "d")

    @Test fun recentTracksGoToTheEndAndKeepTheirOrder() {
        assertEquals(listOf("a", "c", "b", "d"), RepeatPolicy.deferRecent(ids, { it }, setOf("b", "d")))
        assertEquals(ids, RepeatPolicy.deferRecent(ids, { it }, emptySet()))
    }

    @Test fun recentTracksAreLeftOutUnlessNothingElseIsLeft() {
        assertEquals(listOf("a", "c"), RepeatPolicy.dropRecent(ids, { it }, setOf("b", "d")))
        assertEquals(ids, RepeatPolicy.dropRecent(ids, { it }, ids.toSet()))
    }
}

class SpectrumTest {
    private fun tone(hz: Double, rate: Int, amplitude: Double = 1.0) = FloatArray(Spectrum.SIZE) { (amplitude * kotlin.math.sin(2 * Math.PI * hz * it / rate)).toFloat() }

    @Test fun silenceGivesNoLevel() {
        assertTrue(Spectrum.bands(FloatArray(Spectrum.SIZE), 44_100).all { it == 0f })
    }

    @Test fun aToneLightsTheBandThatHoldsItsFrequency() {
        val bands = Spectrum.bands(tone(1_000.0, 44_100), 44_100)
        val loudest = bands.indices.maxByOrNull { bands[it] }!!
        val from = 40f * (16_000f / 40f).let { Math.pow(it.toDouble(), loudest / 32.0).toFloat() }
        val to = 40f * Math.pow((16_000f / 40f).toDouble(), (loudest + 1) / 32.0).toFloat()
        assertTrue("1000 Hz lies in the band from $from to $to Hz", 1_000f in (from * .85f)..(to * 1.15f))
        assertTrue(bands[loudest] > .8f)
    }

    @Test fun aQuieterToneGivesALowerLevel() {
        val loud = Spectrum.bands(tone(2_000.0, 48_000, 1.0), 48_000).max()
        val quiet = Spectrum.bands(tone(2_000.0, 48_000, 0.05), 48_000).max()
        assertTrue(quiet < loud)
    }
}
