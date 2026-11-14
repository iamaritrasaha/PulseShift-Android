package com.pulseshift.app.domain.model

enum class EffectType(val id: Int, val displayName: String, val description: String) {
    STATIC(0, "Static Color", "Solid illumination across all 6 LEDs or individual custom colors"),
    BREATHING(1, "Breathing", "Soft sine-wave illumination rise and fall"),
    RAINBOW(2, "Rainbow Wave", "Fluid full-spectrum color flow across the light strip"),
    CHASE(3, "Color Chase", "High-velocity runner beam traversing LEDs 1 to 6"),
    RPM_BAR(4, "RPM Bar", "Dynamic tachometer display mode mapping live engine RPM");

    companion object {
        fun fromId(id: Int): EffectType = entries.find { it.id == id } ?: STATIC
    }
}
