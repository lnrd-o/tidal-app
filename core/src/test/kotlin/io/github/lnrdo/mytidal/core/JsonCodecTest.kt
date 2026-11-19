package io.github.lnrdo.mytidal.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JsonCodecTest {
    @Test fun collectionCachePreservesPlaylistOwnershipAndTypes() {
        val releases = listOf(Release("playlist-42", "Evening", "Owner", "https://example.org/art.jpg", "12 tracks", ReleaseType.PLAYLIST, "account-7"),
            Release("album-10", "Album", "Artist"))
        assertEquals(releases, JsonCodec.decodeReleases(JsonCodec.encodeReleases(releases)))
    }

    @Test fun preservesLocalTracksAndMetadataAcrossPersistence() {
        val track = Track("local:123", "Track \"one\"", "Artist", "Album", artwork = "file:///private/art.jpg", durationMs = 253000,
            localUri = "content://documents/audio/42", quality = "FLAC", addedAt = 1700000000)
        assertEquals(listOf(track), JsonCodec.decodeTracks(JsonCodec.encodeTracks(listOf(track))))
    }

    @Test fun unwrapsCollectionItems() {
        val values = JsonCodec.items(JSONObject("""{"items":[{"created":"today","item":{"id":42,"title":"Song"}},{"id":43,"title":"Other"}]}"""))
        assertEquals(listOf("42", "43"), values.map { it.get("id").toString() })
    }

    @Test fun formatsDurationsWithoutNegativeTime() {
        assertEquals("0:00", formatDuration(-1000))
        assertEquals("4:03", formatDuration(243000))
        assertEquals("65:01", formatDuration(3901000))
    }
}
