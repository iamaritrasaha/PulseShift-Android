package com.pulseshift.app.domain.model

/**
 * Explicit outcome for applying the 1,000–6,000 RPM shift light profile.
 * Distinguishes between complete failure, active in volatile RAM only,
 * and confirmed persistence in ESP32 NVS flash storage.
 */
enum class ProfileApplyResult {
    /** Transaction failed or was rejected by controller or transport error */
    FAILED,

    /** Profile committed to volatile RAM; NVS flash save unconfirmed or not persisted */
    APPLIED_ONLY,

    /** Profile committed to volatile RAM AND confirmed saved to ESP32 NVS (requires ACK.isSuccess && ACK.isPersisted) */
    PERSISTED;

    val isSuccess: Boolean get() = this != FAILED
    val isPersisted: Boolean get() = this == PERSISTED
}
