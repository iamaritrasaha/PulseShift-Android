package com.pulseshift.app.domain.model

/**
 * Lifecycle of one device command surfaced in the Diagnostics command
 * history: Sending -> Accepted (firmware ACK) -> Applied / Saved -> or
 * Failed with the firmware status reason. A successful Bluetooth write alone
 * is never treated as proof of execution.
 */
enum class DeviceCommandPhase { SENDING, ACCEPTED, APPLIED, SAVED, UNVERIFIED, FAILED }

data class DeviceCommandOutcome(
    val id: Long,
    val label: String,
    val phase: DeviceCommandPhase,
    val detail: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)

/** Firmware calibration session state queried via CAL_STATUS (0x46). */
data class CalibrationSessionStatus(
    val sessionActive: Boolean,
    val pointCount: Int,
    val captureActive: Boolean,
    val captureSamples: Int
)

/** Result of CAL_COMPUTE (0x45): fitted scale/offset and validation evidence. */
data class CalibrationFitResult(
    val success: Boolean,
    val multiplier: Float = 0f,
    val offset: Float = 0f,
    val maxResidualRpm: Int = 0,
    val pointsUsed: Int = 0,
    val reason: String = ""
)
