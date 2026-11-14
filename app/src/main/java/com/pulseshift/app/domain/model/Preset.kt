package com.pulseshift.app.domain.model

data class Preset(
    val id: String,
    val name: String,
    val effect: EffectType,
    val ledColors: List<RgbColor>,
    val brightness: Int = 200,
    val effectSpeed: Int = 50,
    val ledDirection: Int = 0,
    val startupAnimation: StartupAnimation = StartupAnimation.SWEEP,
    val startupDurationMs: Int = 2000,
    val isCustom: Boolean = false,
    val shiftLightConfig: ShiftLightConfig? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        val DefaultPresets = listOf(
            Preset(
                id = "preset_brand",
                name = "PulseShift Tachometer (1k-6k)",
                effect = EffectType.RPM_BAR,
                ledColors = listOf(
                    RgbColor.NeonGreen,
                    RgbColor.NeonGreen,
                    RgbColor.AmberWarning,
                    RgbColor.AmberWarning,
                    RgbColor.SunsetOrange,
                    RgbColor.RacingRed
                ),
                brightness = 220,
                effectSpeed = 50,
                shiftLightConfig = ShiftLightConfig(
                    thresholds = listOf(1000, 2000, 3000, 4000, 5000, 6000),
                    warningRpm = 6500,
                    warningStyle = WarningStyle.FLASH,
                    colors = listOf(
                        RgbColor.NeonGreen,
                        RgbColor.NeonGreen,
                        RgbColor.AmberWarning,
                        RgbColor.AmberWarning,
                        RgbColor.SunsetOrange,
                        RgbColor.RacingRed
                    )
                )
            ),
            Preset(
                id = "preset_aurora",
                name = "Aurora Borealis",
                effect = EffectType.RAINBOW,
                ledColors = listOf(
                    RgbColor(6, 182, 212),
                    RgbColor(16, 185, 129),
                    RgbColor(52, 211, 153),
                    RgbColor(99, 102, 241),
                    RgbColor(139, 92, 246),
                    RgbColor(168, 85, 247)
                ),
                brightness = 220,
                effectSpeed = 45
            ),
            Preset(
                id = "preset_ocean",
                name = "Pacific Deep",
                effect = EffectType.CHASE,
                ledColors = listOf(
                    RgbColor(14, 165, 233),
                    RgbColor(2, 132, 199),
                    RgbColor(3, 105, 161),
                    RgbColor(30, 64, 175),
                    RgbColor(67, 56, 202),
                    RgbColor(99, 102, 241)
                ),
                brightness = 210,
                effectSpeed = 55
            ),
            Preset(
                id = "preset_sunset",
                name = "Canyon Sunset",
                effect = EffectType.BREATHING,
                ledColors = listOf(
                    RgbColor(249, 115, 22),
                    RgbColor(245, 158, 11),
                    RgbColor(239, 68, 68),
                    RgbColor(217, 70, 239),
                    RgbColor(168, 85, 247),
                    RgbColor(139, 92, 246)
                ),
                brightness = 230,
                effectSpeed = 40
            ),
            Preset(
                id = "preset_racing",
                name = "Track Apex",
                effect = EffectType.STATIC,
                ledColors = listOf(
                    RgbColor.RacingRed,
                    RgbColor.RacingRed,
                    RgbColor.PureWhite,
                    RgbColor.PureWhite,
                    RgbColor.RacingRed,
                    RgbColor.RacingRed
                ),
                brightness = 255,
                effectSpeed = 70
            ),
            Preset(
                id = "preset_rainbow",
                name = "Prism Flow",
                effect = EffectType.RAINBOW,
                ledColors = listOf(
                    RgbColor(239, 68, 68),
                    RgbColor(245, 158, 11),
                    RgbColor(16, 185, 129),
                    RgbColor(6, 182, 212),
                    RgbColor(59, 130, 246),
                    RgbColor(139, 92, 246)
                ),
                brightness = 200,
                effectSpeed = 60
            )
        )
    }
}
