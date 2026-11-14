package com.pulseshift.app.domain.model

/**
 * State of a dual-slot authenticated firmware Over-The-Air (OTA) upgrade.
 */
sealed interface OtaUpdateState {
    data object Idle : OtaUpdateState

    data class Validating(
        val totalBytes: Long,
        val sha256Hex: String,
        val targetSlot: Int
    ) : OtaUpdateState

    data class Transferring(
        val bytesSent: Long,
        val totalBytes: Long,
        val progressPercent: Float,
        val currentSlot: Int
    ) : OtaUpdateState

    data class Verifying(
        val targetSlot: Int
    ) : OtaUpdateState

    data class Rebooting(
        val targetSlot: Int,
        val secondsRemaining: Int
    ) : OtaUpdateState

    data class Success(
        val previousVersion: String,
        val newVersion: String,
        val activeSlot: Int
    ) : OtaUpdateState

    data class Failed(
        val reason: String,
        val canRetry: Boolean = true
    ) : OtaUpdateState
}
