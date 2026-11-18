package com.pulseshift.app.domain.model

data class LightingState(
    val isPowerOn: Boolean = true,
    val brightness: Int = 200, // 0 - 255
    val effect: EffectType = EffectType.STATIC,
    val effectSpeed: Int = 50, // 0 - 100
    val globalColor: RgbColor = RgbColor.BrandViolet,
    val ledColors: List<RgbColor> = List(6) { RgbColor.BrandViolet },
    val isShiftLightActive: Boolean = false,
    val isPendingSync: Boolean = false,
    val isHardwareConfirmed: Boolean = false,
    val isSyncUnknown: Boolean = false,
    // Direction and startup configuration live in the same unified state as
    // everything else so no screen reads a second, drifting source.
    val ledDirection: Int = 0, // 0 = forward, 1 = reversed
    val startupAnimation: StartupAnimation = StartupAnimation.SWEEP,
    val startupDurationMs: Int = 2000
) {
    init {
        require(brightness in 0..255) { "Brightness must be 0-255" }
        require(effectSpeed in 0..100) { "Speed must be 0-100" }
        require(ledColors.size == 6) { "PulseShift strip requires exactly 6 LED colors" }
    }

    val isStartupLooping: Boolean get() = (startupDurationMs and 0x8000) != 0
    val startupCycleDurationMs: Int get() = startupDurationMs and 0x7FFF

    val brightnessPercent: Int
        get() = ((brightness / 255f) * 100).toInt()

    val isDirectionReversed: Boolean
        get() = ledDirection == 1

    fun matchesPreset(preset: Preset): Boolean {
        if (effect != preset.effect) return false
        if (brightness != preset.brightness) return false
        if (effectSpeed != preset.effectSpeed) return false
        if (ledDirection != preset.ledDirection) return false
        if (startupAnimation != preset.startupAnimation) return false
        if (startupDurationMs != preset.startupDurationMs) return false
        if (ledColors != preset.ledColors) return false
        return true
    }
}
