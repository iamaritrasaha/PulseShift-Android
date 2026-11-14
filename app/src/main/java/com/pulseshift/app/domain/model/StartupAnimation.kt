package com.pulseshift.app.domain.model

enum class StartupAnimation(
    val id: Int,
    val displayName: String,
    val subtitle: String,
    val description: String
) {
    NONE(0, "None", "Disabled", "No power-on animation. Instantly applies stored illumination."),
    SWEEP(1, "Sweep", "Ignition Wipe", "Sequential left-to-right ignition light sweep traversing LEDs 1 to 6."),
    WAVE(2, "Wave", "Wave Crest", "Smooth dual-crested spatial wave power-on greeting."),
    PULSE(3, "Pulse", "Heartbeat Greeting", "Dynamic dual-heartbeat motorcycle power-on greeting pulse.");

    companion object {
        fun fromId(id: Int): StartupAnimation = entries.find { it.id == id } ?: SWEEP
    }
}

data class StartupConfig(
    val isEnabled: Boolean = true,
    val animation: StartupAnimation = StartupAnimation.SWEEP,
    val durationSec: Float = 2.0f
)
