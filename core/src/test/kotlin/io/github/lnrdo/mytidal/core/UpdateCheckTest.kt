package io.github.lnrdo.mytidal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test fun aLaterVersionIsNewerAtEveryPosition() {
        assertTrue(UpdateCheck.isNewer("0.6.1", "0.6.2"))
        assertTrue(UpdateCheck.isNewer("0.6.9", "0.7.0"))
        assertTrue(UpdateCheck.isNewer("0.9.0", "1.0.0"))
        assertTrue("Ten is later than nine", UpdateCheck.isNewer("0.6.9", "0.6.10"))
    }

    @Test fun theSameOrAnEarlierVersionIsNotNewer() {
        assertFalse(UpdateCheck.isNewer("0.6.1", "0.6.1"))
        assertFalse(UpdateCheck.isNewer("0.6.1", "0.6.0"))
        assertFalse(UpdateCheck.isNewer("1.0.0", "0.9.9"))
        assertFalse("A missing part counts as zero", UpdateCheck.isNewer("1.0", "1.0.0"))
    }

    @Test fun aVersionThatCannotBeReadIsNeverNewer() {
        assertFalse(UpdateCheck.isNewer("0.6.1", "latest"))
        assertFalse(UpdateCheck.isNewer("unknown", "0.6.2"))
        assertFalse(UpdateCheck.isNewer("0.6.1", ""))
    }

    @Test fun aTestVersionIsNeverOffered() {
        assertFalse(UpdateCheck.isNewer("0.7.0", "0.7.1-rc1"))
        assertFalse(UpdateCheck.isNewer("0.7.0", "0.8.0+build5"))
        val json = """[
            {"tag_name":"v0.7.1-rc1","html_url":"https://example.com/rc","draft":false,"prerelease":true},
            {"tag_name":"v0.7.0","html_url":"https://example.com/0.7.0","draft":false,"prerelease":true}]"""
        assertEquals("0.7.0", UpdateCheck.newest(json)?.version)
    }

    @Test fun theNewestReleaseWinsWhateverTheOrderOfTheList() {
        val json = """[
            {"tag_name":"v0.6.0","html_url":"https://example.com/0.6.0","draft":false,"prerelease":true},
            {"tag_name":"v0.6.2","html_url":"https://example.com/0.6.2","draft":false,"prerelease":true},
            {"tag_name":"v0.6.1","html_url":"https://example.com/0.6.1","draft":false,"prerelease":true}]"""
        assertEquals(ReleaseNotice("0.6.2", "https://example.com/0.6.2"), UpdateCheck.newest(json))
    }

    @Test fun draftsAndUnreadableTagsAreLeftOut() {
        val json = """[
            {"tag_name":"v9.9.9","html_url":"https://example.com/9","draft":true},
            {"tag_name":"nightly","html_url":"https://example.com/n","draft":false},
            {"tag_name":"v0.6.1","html_url":"https://example.com/0.6.1","draft":false}]"""
        assertEquals("0.6.1", UpdateCheck.newest(json)?.version)
    }

    @Test fun noReleasesAndBrokenAnswersGiveNothing() {
        assertNull(UpdateCheck.newest("[]"))
        assertNull(UpdateCheck.newest("{\"message\":\"Not Found\"}"))
        assertNull(UpdateCheck.newest("not json"))
    }
}
