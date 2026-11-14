package com.pulseshift.app.domain.model

data class RpmStatusFlags(
    val isCandidateFresh: Boolean,
    val isCalibrationVerified: Boolean,
    val isProductionRpmMappingEnabled: Boolean,
    val isPhysicalShiftLightsEnabled: Boolean,
    val isCanHealthy: Boolean,
    val isRecentCanTrafficDetected: Boolean,
    val isFlashRecordingActive: Boolean,
    val isPreviousCapturePreserved: Boolean
) {
    companion object {
        fun fromByte(flags: Byte): RpmStatusFlags {
            val b = flags.toInt() and 0xFF
            return RpmStatusFlags(
                isCandidateFresh = (b and 0x01) != 0,
                isCalibrationVerified = (b and 0x02) != 0,
                isProductionRpmMappingEnabled = (b and 0x04) != 0,
                isPhysicalShiftLightsEnabled = (b and 0x08) != 0,
                isCanHealthy = (b and 0x10) != 0,
                isRecentCanTrafficDetected = (b and 0x20) != 0,
                isFlashRecordingActive = (b and 0x40) != 0,
                isPreviousCapturePreserved = (b and 0x80) != 0
            )
        }
    }
}

data class RpmTelemetryState(
    val protocolVersion: Int,
    val statusFlags: RpmStatusFlags,
    val sequence: Int,
    val uptimeMs: Long,
    val provisionalRpm: Int, // 65535 = unavailable
    val rawCandidateCanValue: Int, // u8
    val captureStage: Int,
    val totalCanFramesReceived: Long,
    val rpmSampleAgeMs: Int, // 65535 = unavailable
    val reserved: Int = 0,
    val timestampReceivedMs: Long = System.currentTimeMillis()
) {
    val isRpmAvailable: Boolean
        get() = statusFlags.isCandidateFresh &&
                provisionalRpm != 65535 &&
                rpmSampleAgeMs != 65535

    val isStale: Boolean
        get() = (System.currentTimeMillis() - timestampReceivedMs > 2000) ||
                (rpmSampleAgeMs != 65535 && rpmSampleAgeMs > 3000)

    val displayRpmValueText: String
        get() = if (isRpmAvailable && !isStale) "$provisionalRpm RPM" else "—"

    val displayLabelText: String
        get() = when {
            !isRpmAvailable || isStale -> "RPM Unavailable"
            statusFlags.isCalibrationVerified -> "Verified Engine RPM"
            else -> "Estimated RPM — Unverified"
        }

    val displaySecondaryDetails: String
        get() = when {
            !statusFlags.isCandidateFresh -> "CAN candidate signal not fresh"
            !statusFlags.isCalibrationVerified -> "Unverified conversion • Raw CAN: $rawCandidateCanValue"
            else -> "Calibration verified • Raw CAN: $rawCandidateCanValue"
        }

    companion object {
        const val RPM_UNAVAILABLE_SENTINEL = 65535
        const val AGE_UNAVAILABLE_SENTINEL = 65535
    }
}
