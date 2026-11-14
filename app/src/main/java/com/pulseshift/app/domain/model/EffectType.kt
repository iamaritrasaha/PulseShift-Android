package com.pulseshift.app.domain.model

enum class EffectType(val id: Int, val displayName: String, val description: String) {
    STATIC(0, "Static Color", "Solid illumination across all 6 LEDs or individual custom colors"),
    BREATHING(1, "Breathing", "Soft sine-wave illumination rise and fall"),
    RAINBOW(2, "Rainbow Wave", "Fluid full-spectrum color flow across the light strip"),
    CHASE(3, "Color Chase", "High-velocity runner beam traversing LEDs 1 to 6"),
    RPM_BAR(4, "RPM Bar", "Verified tachometer mode; requires a calibration record bound to the current decoder"),
    PROVISIONAL_PREVIEW(5, "Provisional Preview", "Unverified live CAN RPM preview with the estimated ×40 mapping; not a calibrated shift cue");

    companion object {
        fun fromId(id: Int): EffectType = entries.find { it.id == id } ?: STATIC
    }
}
