package io.github.lnrdo.mytidal

import android.net.Uri
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.offline.DashDownloader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import io.github.lnrdo.mytidal.core.StreamInfo
import io.github.lnrdo.mytidal.core.Track
import io.github.lnrdo.mytidal.data.DownloadFiles
import io.github.lnrdo.mytidal.data.DownloadMigration
import io.github.lnrdo.mytidal.playback.AudioSources
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/**
 * A download of an earlier version is a set of FLAC segments in the private cache. The test builds such a download from the FLAC file of the tests,
 * moves it into the download folder and checks the file, the library and the leftovers, and checks that a download that cannot move stays as it was.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class DownloadMigrationTest {
    @get:Rule val storage: GrantPermissionRule = if (Build.VERSION.SDK_INT <= 28) GrantPermissionRule.grant(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) else GrantPermissionRule.grant()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OpenTuneApp
    private val directory get() = File(app.filesDir, "migration-test")
    private val track = Track("2100001", "Migration Walk", "Test Artist", "Test Album", trackNumber = 3, localUri = "opentune-offline://${AudioSources.safeId("2100001")}", quality = "FLAC")
    private lateinit var frames: ByteArray

    @Before fun buildOldDownload() {
        cleanUp()
        val flac = InstrumentationRegistry.getInstrumentation().context.assets.open("night-walk.flac").use { it.readBytes() }
        // "fLaC", then metadata blocks that end with one that carries the last-block flag, then the frames.
        var position = 4
        var last = false
        while (!last) { last = flac[position].toInt() and 0x80 != 0; position += 4 + (((flac[position + 1].toInt() and 255) shl 16) or ((flac[position + 2].toInt() and 255) shl 8) or (flac[position + 3].toInt() and 255)) }
        frames = flac.copyOfRange(position, flac.size)
        val streamInfo = flac.copyOfRange(8, 42)
        directory.mkdirs()
        File(directory, "init.mp4").writeBytes(initialization(streamInfo))
        // The second segment starts at a frame header, as the segments of a real download do.
        val split = (frames.size / 2 until frames.size - 1).first { frames[it] == 0xFF.toByte() && (frames[it + 1].toInt() and 0xFE) == 0xF8 }
        File(directory, "seg1.m4s").writeBytes(box("styp", ByteArray(8)) + box("moof", ByteArray(12)) + box("mdat", frames.copyOfRange(0, split)))
        File(directory, "seg2.m4s").writeBytes(box("styp", ByteArray(8)) + box("moof", ByteArray(12)) + box("mdat", frames.copyOfRange(split, frames.size)))
        val manifest = """<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="static" mediaPresentationDuration="PT2S" minBufferTime="PT1S" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011">
            <Period><AdaptationSet mimeType="audio/mp4" segmentAlignment="true"><Representation id="1" codecs="flac" bandwidth="1000000" audioSamplingRate="96000">
            <BaseURL>${Uri.fromFile(directory)}/</BaseURL><SegmentTemplate timescale="1" duration="1" startNumber="1" initialization="init.mp4" media="seg${'$'}Number${'$'}.m4s"/>
            </Representation></AdaptationSet></Period></MPD>"""
        val stream = StreamInfo(dashManifest = manifest, mimeType = MimeTypes.APPLICATION_MPD, codec = "flac", quality = "LOSSLESS", bitDepth = 24, sampleRate = 96000)
        val item = MediaItem.Builder().setUri(app.sources.manifestUri(stream)).setMimeType(MimeTypes.APPLICATION_MPD).build()
        DashDownloader.Factory(app.sources.downloadSource()).setExecutor { it.run() }.create(item).download(null)
        app.sources.saveOffline(track.id, stream)
        app.library.save(track)
        // The player reads file addresses without the cache. A real download has web addresses, so the files go and only the cache stays.
        directory.listFiles()?.forEach { it.delete() }
    }

    @After fun cleanUp() {
        app.library.get(track.id)?.localUri?.takeIf { !it.startsWith("opentune-offline:") }?.let { runCatching { app.downloadFiles.delete(Uri.parse(it)) } }
        runCatching { if (app.sources.offlineFile(track.id).exists()) app.sources.removeOffline(track.id) }
        app.library.removeLocal(track.id)
        directory.deleteRecursively()
    }

    @Test fun anOldDownloadBecomesOneTaggedFlacFileAndTheSegmentsGo() = runBlocking {
        val migration = DownloadMigration(app)
        assertEquals(listOf(track.id), migration.pending().map { it.id })
        migration.move(track)
        val moved = app.library.get(track.id)!!
        assertFalse("The track points to the file", moved.localUri!!.startsWith("opentune-offline:"))
        assertTrue(app.downloadFiles.readable(Uri.parse(moved.localUri)))
        assertTrue(app.library.isDownloadFile(DownloadFiles.keyOf("Test Artist - Migration Walk.flac")))
        assertTrue("The segments are removed", !app.sources.offlineFile(track.id).exists())
        assertTrue(migration.pending().isEmpty())
        val file = app.contentResolver.openInputStream(Uri.parse(moved.localUri))!!.use { it.readBytes() }
        assertEquals("fLaC", file.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        val head = file.copyOfRange(0, file.size - frames.size).toString(Charsets.ISO_8859_1)
        assertTrue("The title is tagged", head.contains("TITLE=Migration Walk"))
        assertTrue("The artist is tagged", head.contains("ARTIST=Test Artist"))
        assertTrue("The track number is tagged", head.contains("TRACKNUMBER=3"))
        assertArrayEquals("The audio frames are the frames of the original", frames, file.copyOfRange(file.size - frames.size, file.size))
    }

    @Test fun aDownloadThatCannotMoveStaysAsItWas() = runBlocking {
        app.sources.cache.keys.filter { it.contains("seg2") }.forEach(app.sources.cache::removeResource)
        val migration = DownloadMigration(app)
        try { migration.move(track); fail("The move of a damaged download succeeded") } catch (_: Exception) { }
        assertEquals("The track still points to its segments", track.localUri, app.library.get(track.id)!!.localUri)
        assertTrue("The segments stay", app.sources.offlineFile(track.id).exists())
        assertFalse(app.library.isDownloadFile(DownloadFiles.keyOf("Test Artist - Migration Walk.flac")))
        assertEquals(listOf(track.id), migration.pending().map { it.id })
        assertEquals("No file is left in the folder", 0, migration.moveAll())
    }

    private fun initialization(streamInfo: ByteArray): ByteArray {
        val config = box("dfLa", ByteArray(4) + byteArrayOf(0, 0, 0, 34) + streamInfo)
        val entry = box("fLaC", ByteArray(28) + config)
        val stsd = box("stsd", ByteArray(4) + byteArrayOf(0, 0, 0, 1) + entry)
        val handler = box("hdlr", ByteArray(8) + "soun".toByteArray() + ByteArray(4))
        return box("ftyp", "iso6".toByteArray()) + box("moov", box("trak", box("mdia", handler + box("minf", box("stbl", stsd)))))
    }

    private fun box(type: String, bytes: ByteArray) = ByteBuffer.allocate(8 + bytes.size).putInt(8 + bytes.size).put(type.toByteArray(Charsets.US_ASCII)).put(bytes).array()
}
