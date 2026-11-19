package io.github.lnrdo.mytidal.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class StreamManifestTest {
    private fun response(manifest: String, mime: String = "application/vnd.tidal.bts") = JSONObject().apply {
        put("manifestMimeType", mime)
        put("manifest", Base64.getEncoder().encodeToString(manifest.toByteArray()))
        put("assetPresentation", "FULL"); put("audioQuality", "LOSSLESS")
        put("bitDepth", 16); put("sampleRate", 44100)
    }

    @Test fun readsActualCodecAndQuality() {
        val result = StreamManifest.parse(response("""{"urls":["https://audio.example/song.flac?token=private"],"mimeType":"audio/flac","codecs":"flac","encryptionType":"NONE"}"""))
        assertEquals("flac", result.codec)
        assertEquals("FLAC · 16-bit · 44.1 kHz", result.qualityLabel)
        assertFalse(result.toString().contains("token=private"))
    }

    @Test fun doesNotTreatPreviewAsFullTrack() {
        val response = response("{}").put("assetPresentation", "PREVIEW")
        val error = assertThrows(ServiceException::class.java) { StreamManifest.parse(response) }
        assertEquals(403, error.status)
    }

    @Test fun rejectsProtectedBts() {
        assertThrows(ProtectedStreamException::class.java) {
            StreamManifest.parse(response("""{"urls":["https://audio.example/song.flac"],"encryptionType":"OLD_AES"}"""))
        }
    }

    @Test fun readsNamespacedDashWithoutFetchingItsUrls() {
        val manifest = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period><AdaptationSet mimeType="audio/mp4"><Representation id="audio" codecs="flac" audioSamplingRate="96000"><BaseURL>https://audio.example/track/</BaseURL></Representation></AdaptationSet></Period></MPD>"""
        val result = StreamManifest.parse(response(manifest, "application/dash+xml").put("sampleRate", 0))
        assertEquals("flac", result.codec)
        assertEquals(96000, result.sampleRate)
        assertEquals(manifest, result.dashManifest)
    }

    @Test fun rejectsDashProtectionAndExternalEntities() {
        assertThrows(ProtectedStreamException::class.java) {
            StreamManifest.parse(response("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period><AdaptationSet><ContentProtection/></AdaptationSet></Period></MPD>""", "application/dash+xml"))
        }
        assertThrows(ServiceException::class.java) {
            StreamManifest.parse(response("""<!DOCTYPE MPD [<!ENTITY xxe SYSTEM "https://example.invalid/secret">]><MPD>&xxe;</MPD>""", "application/dash+xml"))
        }
    }

    @Test fun rejectsUnsafeAudioAddresses() {
        listOf("http://audio.example/a", "file:///etc/passwd", "https://user:password@audio.example/a", "data:audio/flac;base64,AA==").forEach { url ->
            assertThrows(ServiceException::class.java) { StreamManifest.requireHttps(url) }
        }
    }
}
