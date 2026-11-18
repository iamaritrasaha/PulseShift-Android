package com.pulseshift.app.domain.model

/**
 * Read-only interpretation of EXISTING ESP32 BLE notifications (RPM at 10 Hz,
 * health at 1 Hz). The optional paired firmware probe writes CAN 0x303[1]
 * into RPM bytes 18-19 with an explicit freshness marker. Legacy firmware
 * continues to work; this experimental prediction is NOT a verified voltmeter.
 */
data class LiveTelemetrySnapshot(
    val receivedFrames: Long?,
    val uptimeSeconds: Long?,
    val candidateRawByte: Int?,
    val candidateAgeMs: Int?,
    val provisionalRpm: Int?,
    val trafficActive: Boolean?,
    val canHealthy: Boolean?,
    val missedFrames: Long?,
    val fifoOverruns: Long?,
    val busErrors: Long?,
    val recordedThisSession: Long?,
    val recordingActive: Boolean?,
    val capturePreserved: Boolean?,
    val storageMounted: Boolean?,
    val storageFull: Boolean?,
    val receiveDegraded: Boolean?,
    val hasFreshRpmPacket: Boolean,
    val hasFreshHealthPacket: Boolean,
    // A null sensor reading means *not available*, never 0 V / 0 degrees.
    val batteryVolts: Double? = null,
    val voltageCandidateRaw: Int? = null,
    val voltageProbeSupported: Boolean = false
) {
    val hasLiveData: Boolean get() = hasFreshRpmPacket || hasFreshHealthPacket

    companion object {
        private const val RPM_PACKET_MAX_AGE_MS = 2_000L
        private const val HEALTH_PACKET_MAX_AGE_MS = 5_000L

        fun from(
            telemetry: RpmTelemetryState?,
            health: CanHealthState?,
            connected: Boolean,
            nowMs: Long = telemetryClockMs()
        ): LiveTelemetrySnapshot {
            fun isCurrent(receivedAt: Long, maxAgeMs: Long): Boolean =
                connected && nowMs >= receivedAt && nowMs - receivedAt <= maxAgeMs

            val rpm = telemetry?.takeIf { isCurrent(it.timestampReceivedMs, RPM_PACKET_MAX_AGE_MS) }
            val can = health?.takeIf { isCurrent(it.timestampReceivedMs, HEALTH_PACKET_MAX_AGE_MS) }
            val candidateValid = rpm?.let {
                it.isRpmAvailable && it.rpmSampleAgeMs <= RPM_PACKET_MAX_AGE_MS
            } == true

            return LiveTelemetrySnapshot(
                receivedFrames = rpm?.totalCanFramesReceived,
                uptimeSeconds = rpm?.uptimeMs?.div(1_000),
                candidateRawByte = rpm?.rawCandidateCanValue?.takeIf { candidateValid },
                candidateAgeMs = rpm?.rpmSampleAgeMs?.takeIf { candidateValid },
                provisionalRpm = rpm?.provisionalRpm?.takeIf { candidateValid },
                trafficActive = rpm?.statusFlags?.isRecentCanTrafficDetected,
                canHealthy = rpm?.statusFlags?.isCanHealthy,
                missedFrames = can?.driverMissedFrames,
                fifoOverruns = can?.hardwareFifoOverruns,
                busErrors = can?.canBusErrors,
                recordedThisSession = can?.recordedFramesInCurrentSession,
                recordingActive = rpm?.statusFlags?.isFlashRecordingActive,
                capturePreserved = rpm?.statusFlags?.isPreviousCapturePreserved,
                storageMounted = can?.healthFlags?.isLittleFsMounted,
                storageFull = can?.healthFlags?.isStorageFull,
                receiveDegraded = can?.healthFlags?.isReceivePerformanceDegraded,
                hasFreshRpmPacket = rpm != null,
                hasFreshHealthPacket = can != null,
                batteryVolts = rpm?.experimentalVoltageVolts,
                voltageCandidateRaw = rpm?.experimentalVoltageRaw,
                voltageProbeSupported = rpm?.experimentalVoltageProbeSupported == true
            )
        }
    }
}
