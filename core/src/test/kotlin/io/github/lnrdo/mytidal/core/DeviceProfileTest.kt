package io.github.lnrdo.mytidal.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    private val s24 = DeviceFacts("samsung", "SM-S928B", "QTI SM8650", 37, 8, 11_000, 3_390, "Adreno (TM) 750", vulkan = true, usbHost = true, lowLatencyAudio = true)
    private val motorola = DeviceFacts("motorola", "motorola edge", "Qualcomm LITO", 30, 8, 5_700, 2_304, "Adreno (TM) 620", vulkan = true, usbHost = true)
    private val huawei = DeviceFacts("HUAWEI", "AGS6-W09", "vendor Kirin710", 29, 8, 7_900, 1_997, "Mali-G51", vulkan = true, usbHost = true, lowLatencyAudio = true)

    @Test fun aRecentSnapdragonHasAnNpuThatCanBeUsed() {
        val p = DeviceProfiles.profile(s24)
        assertEquals(NpuStatus.USABLE, p.npu); assertTrue(p.npuName!!.contains("v75"))
        assertEquals(DeviceTier.HIGH, p.tier)
        assertTrue(p.recommendation.neuralRhythm); assertEquals(2, p.recommendation.workers); assertEquals(12, p.recommendation.listenAhead)
    }

    @Test fun anOlderSnapdragonHasADspThatTheRuntimeDoesNotSupport() {
        val p = DeviceProfiles.profile(motorola)
        assertEquals(NpuStatus.OLDER_GENERATION, p.npu)
        assertFalse(p.recommendation.neuralRhythm)
        assertEquals(DeviceTier.MID, p.tier)
        assertEquals(8, p.recommendation.listenAhead)
        // The same chip model number says the same on a newer Android.
        assertEquals(NpuStatus.OLDER_GENERATION, DeviceProfiles.profile(motorola.copy(chip = "QTI SM7250", sdk = 33)).npu)
    }

    @Test fun aKirinWithoutAnNpuIsALowDevice() {
        val p = DeviceProfiles.profile(huawei)
        assertEquals(NpuStatus.NONE, p.npu)
        assertEquals(DeviceTier.LOW, p.tier)
        assertFalse(p.recommendation.neuralRhythm); assertTrue(p.recommendation.genreAi); assertEquals(1, p.recommendation.workers); assertEquals(6, p.recommendation.listenAhead)
        assertEquals(NpuStatus.OTHER_RUNTIME, DeviceProfiles.profile(huawei.copy(chip = "vendor Kirin990")).npu)
    }

    @Test fun otherMakersHaveNpusThatTheBundledRuntimeCannotUse() {
        assertEquals(NpuStatus.OTHER_RUNTIME, DeviceProfiles.profile(DeviceFacts("Google", "Pixel 9", "Google GS401", 35, 8, 12_000, 3_100)).npu)
        assertEquals(NpuStatus.OTHER_RUNTIME, DeviceProfiles.profile(DeviceFacts("Xiaomi", "x", "MTK MT6989", 34, 8, 12_000, 3_300)).npu)
        assertEquals(NpuStatus.NONE, DeviceProfiles.profile(DeviceFacts("x", "y", null, 30, 4, 3_000, 1_800)).npu)
    }

    @Test fun theNpuNeedsAndroidTwelveOrLater() {
        assertEquals(NpuStatus.NONE, DeviceProfiles.profile(s24.copy(sdk = 30)).npu)
    }

    @Test fun aWeakDeviceIsLowAndAGoodOneWithoutAnNpuIsHighWhenItHasPowerAndMemory() {
        assertEquals(DeviceTier.LOW, DeviceProfiles.profile(DeviceFacts("x", "y", "mt6761", 29, 4, 2_000, 1_800)).tier)
        assertEquals(DeviceTier.HIGH, DeviceProfiles.profile(DeviceFacts("x", "y", "something", 33, 8, 12_000, 3_000)).tier)
    }

    @Test fun theFingerprintChangesWithTheDevice() {
        assertNotEquals(DeviceProfiles.profile(s24).fingerprint, DeviceProfiles.profile(motorola).fingerprint)
        assertEquals(DeviceProfiles.profile(s24).fingerprint, DeviceProfiles.profile(s24.copy(ramMb = 9_000)).fingerprint)
    }
}
