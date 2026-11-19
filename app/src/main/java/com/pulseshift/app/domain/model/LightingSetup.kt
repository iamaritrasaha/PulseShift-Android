package com.pulseshift.app.domain.model

enum class RpmMappingMode(val label: String) {
    AUTOMATIC("Automatic"),
    GROUPED("Grouped"),
    INDIVIDUAL("Per LED"),
    LEGACY_GROUPED("Grouped")
}

data class RpmMapping(
    val mode: RpmMappingMode,
    val activeLedCount: Int,
    val thresholds: List<Int>,
    val groupSizes: List<Int> = emptyList()
) {
    fun activeCount(installed: Int): Int = if (activeLedCount == 0) installed else minOf(installed, activeLedCount)

    fun valid(installed: Int, warning: Int): Boolean {
        if (installed !in 1..64 || activeLedCount !in 0..installed || warning !in 0..20_000 ||
            thresholds.size > 64 || thresholds.any { it !in 0..warning } ||
            thresholds.zipWithNext().any { (a, b) -> a > b }) return false
        val count = activeCount(installed)
        return when (mode) {
            RpmMappingMode.LEGACY_GROUPED -> thresholds.isEmpty() && groupSizes.isEmpty()
            RpmMappingMode.AUTOMATIC -> thresholds.size == 2 && groupSizes.isEmpty()
            RpmMappingMode.INDIVIDUAL -> thresholds.size == count && groupSizes.isEmpty()
            RpmMappingMode.GROUPED -> groupSizes.size == thresholds.size &&
                groupSizes.all { it in 1..count } && groupSizes.sum() == count
        }
    }

    fun expand(count: Int, legacy: List<Int>): List<Int> = when (mode) {
        RpmMappingMode.AUTOMATIC -> distributedThresholds(count, thresholds.first(), thresholds.last())
        RpmMappingMode.INDIVIDUAL -> List(count) { thresholds.getOrElse(it) { thresholds.last() } }
        RpmMappingMode.GROUPED -> {
            val pixels = groupSizes.flatMapIndexed { index, size -> List(size) { thresholds[index] } }
            List(count) { pixels.getOrElse(it) { pixels.last() } }
        }
        RpmMappingMode.LEGACY_GROUPED -> List(count) { legacy[ledZoneForPixel(it, count)] }
    }
}

fun distributedThresholds(count: Int, minimum: Int, maximum: Int): List<Int> = List(count) { index ->
    if (count == 1) minimum else minimum + ((maximum - minimum) * index + (count - 1) / 2) / (count - 1)
}

data class AnimationSettings(val enabled: Boolean, val pattern: StartupAnimation, val durationMs: Int) {
    val valid: Boolean get() = pattern != StartupAnimation.NONE && durationMs in 100..10_000
}

data class LightingSetup(
    val installedLedCount: Int,
    val mapping: RpmMapping,
    val boot: AnimationSettings,
    val ambient: AnimationSettings,
    val warningRpm: Int
) {
    val valid: Boolean get() = mapping.valid(installedLedCount, warningRpm) && boot.valid && ambient.valid

    fun resized(installed: Int, active: Int): LightingSetup {
        val before = mapping.activeCount(installedLedCount)
        val after = if (active == 0) installed else active
        val updated = when (mapping.mode) {
            RpmMappingMode.INDIVIDUAL -> mapping.copy(thresholds = mapping.expand(after, emptyList()))
            RpmMappingMode.GROUPED -> {
                val sizes = mapping.groupSizes.toMutableList()
                val rpm = mapping.thresholds.toMutableList()
                var excess = before - after
                while (excess > 0 && excess >= sizes.last()) {
                    excess -= sizes.removeAt(sizes.lastIndex)
                    rpm.removeAt(rpm.lastIndex)
                }
                sizes[sizes.lastIndex] -= excess
                mapping.copy(thresholds = rpm, groupSizes = sizes)
            }
            else -> mapping
        }
        return copy(installedLedCount = installed, mapping = updated.copy(activeLedCount = active))
    }
}

enum class AmbientPlayback(val label: String) {
    OFF("Off"), PLAYING("Playing"), PAUSED_BY_RPM("Paused by RPM"),
    SUPPRESSED_BY_SAFETY("Suppressed by Safety"), WAITING("Waiting"), UNSUPPORTED("Unsupported")
}

data class AnimationStatus(val playback: AmbientPlayback, val canPreview: Boolean)
