package com.pulseshift.app.domain.model

enum class PresetApplyResult {
    PERSISTED,
    READBACK_CONFIRMED,
    APPLIED_NEEDS_READBACK,
    COMMIT_UNKNOWN;

    fun message(presetName: String): String = when (this) {
        PERSISTED -> "Preset '${presetName}' applied and saved to ESP32 NVS for reboot."
        READBACK_CONFIRMED -> "Preset '${presetName}' active in RAM, but NVS save unconfirmed. Use Save to ESP32."
        APPLIED_NEEDS_READBACK -> "Preset '${presetName}' applied in RAM; resync and save before reboot."
        COMMIT_UNKNOWN -> "Preset '${presetName}' commit unverified. Resync with the ESP32."
    }
}
