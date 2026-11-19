package io.github.lnrdo.mytidal.core

/** What can be read about a device: the chip, the memory, the graphics and the audio features. Anything that is not known is null or false. */
data class DeviceFacts(
    val maker: String? = null,
    val model: String? = null,
    /** The name of the chip as the system gives it ("QTI SM8650", "Qualcomm LITO", "vendor Kirin710"). */
    val chip: String? = null,
    val sdk: Int = 0,
    val cores: Int = 1,
    val ramMb: Int = 0,
    /** The top frequency of the fastest core, in megahertz, or 0 when it is not known. */
    val maxMhz: Int = 0,
    val gpu: String? = null,
    val vulkan: Boolean = false,
    val usbHost: Boolean = false,
    val lowLatencyAudio: Boolean = false,
)

/** Whether the device has a neural processor, and whether D'JAI's runtime can use it. */
enum class NpuStatus {
    /** A Qualcomm Hexagon of the generation that the bundled runtime supports (version 68 and later). */
    USABLE,
    /** The chip has an AI engine, but of a generation that the bundled runtime does not support. */
    OLDER_GENERATION,
    /** The chip has an AI engine that only its maker's own software development kit can use (Huawei, MediaTek, Samsung, Google). */
    OTHER_RUNTIME,
    /** The chip has no neural processor, or none that is known. */
    NONE,
}

/** How much work the device can be given: the analysis of songs, the neural models and listening ahead. */
enum class DeviceTier { HIGH, MID, LOW }

/** The settings that suit a device. D'JAI applies them the first time that it runs on it, and the listener can apply them again. */
data class Recommendation(
    val neuralRhythm: Boolean,
    val genreAi: Boolean,
    val genreOnGpu: Boolean,
    /** Songs prepared at the same time. */
    val workers: Int,
    /** Songs ahead that D'JAI listens to. */
    val listenAhead: Int,
)

/** A device as D'JAI understands it. [fingerprint] changes when the device does, so that the settings are tuned again on another one. */
class DeviceProfile(val facts: DeviceFacts, val npu: NpuStatus, val npuName: String?, val tier: DeviceTier, val recommendation: Recommendation) {
    val fingerprint: String get() = "${facts.maker}|${facts.model}|${facts.chip}|${facts.sdk}"
}

object DeviceProfiles {
    /** The Hexagon version of a Qualcomm chip by its model number (SM8550 is version 73), or null when it is not known. */
    private fun hexagonVersion(model: Int): Int? = when (model) {
        8250, 8350 -> if (model == 8250) 66 else 68
        8450, 8475, 7475, 7675 -> 69
        8550, 7550, 7635, 8635 -> 73
        8650, 8735 -> 75
        8750, 8845, 8850 -> 79
        else -> if (model >= 8850) 81 else null
    }

    private fun qualcommModel(chip: String?): Int? = chip?.let { Regex("SM(\\d{4})", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** The neural processor of the chip, as its maker names it, and whether the bundled runtime (Qualcomm's QNN) can use it. */
    fun npuOf(facts: DeviceFacts): Pair<NpuStatus, String?> {
        val chip = facts.chip.orEmpty().lowercase()
        val hardware = facts.maker.orEmpty().lowercase()
        val model = qualcommModel(facts.chip)
        if (chip.contains("qti") || chip.contains("qualcomm") || chip.contains("snapdragon") || model != null || chip.contains("lito") || chip.contains("kona") || chip.contains("sdm")) {
            val version = model?.let(::hexagonVersion)
            return when {
                version != null && version >= 68 && facts.sdk >= 31 -> NpuStatus.USABLE to "Qualcomm Hexagon NPU (v$version)"
                version != null && version >= 68 -> NpuStatus.NONE to "Qualcomm Hexagon NPU (v$version): needs Android 12 or later"
                version != null -> NpuStatus.OLDER_GENERATION to "Qualcomm Hexagon DSP (v$version)"
                model != null && model < 8350 -> NpuStatus.OLDER_GENERATION to "Qualcomm Hexagon DSP"
                // A Qualcomm chip on Android 11 or before is named by its platform ("lito", "kona"): those are the 7 and 8 series before the 888.
                model == null && facts.sdk < 31 -> NpuStatus.OLDER_GENERATION to "Qualcomm Hexagon DSP"
                else -> NpuStatus.NONE to null
            }
        }
        return when {
            chip.contains("kirin") -> {
                val n = Regex("(\\d{3})").find(chip)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                // The Kirin 810 and 820 and the 9 series have the Da Vinci NPU; the 710 and the other 7 series do not.
                if (n in 800..999) NpuStatus.OTHER_RUNTIME to "Huawei Da Vinci NPU" else NpuStatus.NONE to null
            }
            chip.contains("mt") || hardware.contains("mediatek") || chip.contains("mediatek") || chip.contains("dimensity") -> NpuStatus.OTHER_RUNTIME to "MediaTek APU"
            chip.contains("tensor") || chip.startsWith("gs") || hardware.contains("google") -> NpuStatus.OTHER_RUNTIME to "Google Tensor TPU"
            chip.contains("exynos") || chip.contains("s5e") || hardware.contains("samsung") && chip.contains("s5e") -> NpuStatus.OTHER_RUNTIME to "Samsung NPU"
            else -> NpuStatus.NONE to null
        }
    }

    fun tierOf(facts: DeviceFacts, npu: NpuStatus): DeviceTier = when {
        npu == NpuStatus.USABLE -> DeviceTier.HIGH
        facts.cores >= 8 && facts.maxMhz >= 2800 && facts.ramMb >= 7000 -> DeviceTier.HIGH
        facts.cores >= 8 && facts.maxMhz >= 2200 && facts.ramMb >= 4000 -> DeviceTier.MID
        facts.cores >= 6 && facts.maxMhz >= 2000 && facts.ramMb >= 5500 -> DeviceTier.MID
        else -> DeviceTier.LOW
    }

    fun recommendationFor(tier: DeviceTier, npu: NpuStatus): Recommendation = Recommendation(
        // The rhythm model is only worth its cost where an NPU runs it: on a CPU it takes 4 to 5 times longer than on the NPU of a flagship.
        neuralRhythm = npu == NpuStatus.USABLE,
        genreAi = true,
        // The genre network is small, and on the phones that were measured the CPU runs it faster than the GPU does.
        genreOnGpu = false,
        workers = if (tier == DeviceTier.HIGH) 2 else 1,
        listenAhead = when (tier) { DeviceTier.HIGH -> 12; DeviceTier.MID -> 8; DeviceTier.LOW -> 6 },
    )

    fun profile(facts: DeviceFacts): DeviceProfile {
        val (npu, name) = npuOf(facts)
        val tier = tierOf(facts, npu)
        return DeviceProfile(facts, npu, name, tier, recommendationFor(tier, npu))
    }
}
