package io.github.lnrdo.mytidal.core

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ListenBrainzTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ListenBrainzClient
    private var token: String? = "secret-token"

    @Before fun setUp() {
        server = MockWebServer(); server.start()
        client = ListenBrainzClient(OkHttpClient(), { server.url("/").toString().trimEnd('/') + "/" }, { token }, "1.2.3")
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun singleListenIsSentWithTheTokenAndOpenTuneMetadata() {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        client.submit(listOf(client.listenJson(Listen(1_700_000_000, "Artist", "Title", "Album", 200_000, 3))))
        val request = server.takeRequest()
        assertEquals("/1/submit-listens", request.path)
        assertEquals("Token secret-token", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("single", body.getString("listen_type"))
        val listen = body.getJSONArray("payload").getJSONObject(0)
        assertEquals(1_700_000_000L, listen.getLong("listened_at"))
        val metadata = listen.getJSONObject("track_metadata")
        assertEquals("Artist", metadata.getString("artist_name"))
        assertEquals("Album", metadata.getString("release_name"))
        assertEquals("OpenTune", metadata.getJSONObject("additional_info").getString("submission_client"))
        assertEquals(3, metadata.getJSONObject("additional_info").getInt("tracknumber"))
    }

    @Test fun severalListensAreImportedTogether() {
        server.enqueue(MockResponse().setBody("{}"))
        client.submit(listOf(1L, 2L).map { client.listenJson(Listen(it, "A", "T$it", "", 0)) })
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("import", body.getString("listen_type"))
        assertEquals(2, body.getJSONArray("payload").length())
        assertFalse(body.getJSONArray("payload").getJSONObject(0).getJSONObject("track_metadata").has("release_name"))
    }

    @Test fun playingNowHasNoTimestamp() {
        server.enqueue(MockResponse().setBody("{}"))
        client.playingNow("A", "T", "", 100)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("playing_now", body.getString("listen_type"))
        assertFalse(body.getJSONArray("payload").getJSONObject(0).has("listened_at"))
    }

    @Test fun rejectionsAndOutagesAreTold() {
        server.enqueue(MockResponse().setResponseCode(401))
        try { client.submit(listOf(client.listenJson(Listen(1, "A", "T", "", 0)))); fail() } catch (error: ServiceException) { assertEquals(401, error.status) }
        server.enqueue(MockResponse().setResponseCode(503))
        try { client.submit(listOf(client.listenJson(Listen(1, "A", "T", "", 0)))); fail() } catch (_: IOException) { }
    }

    @Test fun validationReportsTheUserOrNull() {
        server.enqueue(MockResponse().setBody("""{"valid":true,"user_name":"someone"}"""))
        assertEquals("someone", client.validate())
        server.enqueue(MockResponse().setBody("""{"valid":false}"""))
        assertNull(client.validate())
    }

    @Test fun aMissingTokenIsReportedBeforeAnyRequest() {
        token = null
        try { client.validate(); fail() } catch (error: ServiceException) { assertEquals(401, error.status) }
        assertEquals(0, server.requestCount)
    }

    @Test fun anInvalidServerAddressIsReported() {
        val broken = ListenBrainzClient(OkHttpClient(), { "not a url" }, { "t" })
        try { broken.validate(); fail() } catch (error: ServiceException) { assertEquals(400, error.status) }
    }
}
