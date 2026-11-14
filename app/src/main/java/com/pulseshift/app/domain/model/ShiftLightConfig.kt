package com.pulseshift.app.domain.model

enum class WarningStyle(val id: Int, val label: String) {
    FLASH(0, "Fast Flash (10Hz)"),
    STROBE(1, "Strobe Blink (20Hz)"),
    SOLID(2, "Solid Over-rev"),
    OFF(3, "Shift Alert Off")
}

data class ShiftLightConfig(
    val thresholds: List<Int> = listOf(3000, 4000, 5000, 6000, 7000, 8000),
    val warningRpm: Int = 8500,
    val warningStyle: WarningStyle = WarningStyle.FLASH,
    val colors: List<RgbColor> = listOf(
        RgbColor.NeonGreen,
        RgbColor.NeonGreen,
        RgbColor.AmberWarning,
        RgbColor.AmberWarning,
        RgbColor.SunsetOrange,
        RgbColor.RacingRed
    ),
    val isProgressive: Boolean = true
) {
    init {
        require(thresholds.size == 6) { "Must provide exactly 6 thresholds" }
        require(colors.size == 6) { "Must provide exactly 6 shift LED colors" }
    }

    fun isStrictlyAscending(): Boolean {
        for (i in 0 until thresholds.size - 1) {
            if (thresholds[i] >= thresholds[i + 1]) return false
        }
        return warningRpm > thresholds.last()
    }
}
