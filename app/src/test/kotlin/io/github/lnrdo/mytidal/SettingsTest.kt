package io.github.lnrdo.mytidal

import io.github.lnrdo.mytidal.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test fun aNewInstallHasLosslessQuality() {
        assertEquals(io.github.lnrdo.mytidal.core.AudioQuality.LOSSLESS, Settings().quality)
    }

    @Test fun turningOnTheDownloadOfSavedTracksTurnsOffTheDownloadOfPlayedTracks() {
        val played = Settings().withDownloadPlayed(true)
        assertTrue(played.downloadPlayed); assertFalse(played.downloadSaved)
        val saved = played.withDownloadSaved(true)
        assertTrue(saved.downloadSaved); assertFalse(saved.downloadPlayed)
    }

    @Test fun turningOnTheDownloadOfPlayedTracksTurnsOffTheDownloadOfSavedTracks() {
        val saved = Settings().withDownloadSaved(true)
        val played = saved.withDownloadPlayed(true)
        assertTrue(played.downloadPlayed); assertFalse(played.downloadSaved)
    }

    @Test fun turningAnOptionOffLeavesTheOtherAsItIs() {
        val saved = Settings().withDownloadSaved(true)
        assertEquals(saved, saved.withDownloadPlayed(false))
        assertEquals(Settings(), saved.withDownloadSaved(false))
        val played = Settings().withDownloadPlayed(true)
        assertEquals(Settings(), played.withDownloadPlayed(false))
    }

    @Test fun bothOptionsCanBeOff() {
        val none = Settings().withDownloadSaved(true).withDownloadSaved(false)
        assertFalse(none.downloadSaved); assertFalse(none.downloadPlayed)
    }
}
