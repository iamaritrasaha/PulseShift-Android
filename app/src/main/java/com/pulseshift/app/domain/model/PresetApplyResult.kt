package com.pulseshift.app.domain.model

enum class PresetApplyResult {
    READBACK_CONFIRMED,
    APPLIED_NEEDS_READBACK,
    COMMIT_UNKNOWN;

    fun message(presetName: String): String = when (this) {
        READBACK_CONFIRMED -> "Preset '$presetName' active in ESP32 RAM. Readback confirmed."
        APPLIED_NEEDS_READBACK -> "ESP32 acknowledged preset '$presetName' in RAM. Resync to verify readback."
        COMMIT_UNKNOWN -> "Preset '$presetName' commit unverified. Resync with the ESP32."
    }
}
