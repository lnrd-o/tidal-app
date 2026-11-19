package io.github.lnrdo.mytidal.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizationTest {
    @Test fun offLeavesTheVolumeAlone() = assertEquals(1f, Normalization.volume("off", -9.0, -8.0), 0f)

    @Test fun trackModeTurnsLoudTracksDown() = assertEquals(0.5012f, Normalization.volume("track", -6.0, -3.0), 0.001f)

    @Test fun albumModeUsesTheAlbumGainAndFallsBackToTheTrack() {
        assertEquals(0.7079f, Normalization.volume("album", -6.0, -3.0), 0.001f)
        assertEquals(0.5012f, Normalization.volume("album", -6.0, null), 0.001f)
    }

    @Test fun quietTracksAreNotAmplified() = assertEquals(1f, Normalization.volume("track", 4.5, 4.5), 0f)

    @Test fun missingOrInvalidGainLeavesTheVolumeAlone() {
        assertEquals(1f, Normalization.volume("track", null, null), 0f)
        assertEquals(1f, Normalization.volume("track", Double.NaN, null), 0f)
        assertEquals(1f, Normalization.volume("unknown", -6.0, -6.0), 0f)
    }

    @Test fun extremeGainsAreLimited() = assertEquals(0.01f, Normalization.volume("track", -90.0, null), 0.0001f)
}
