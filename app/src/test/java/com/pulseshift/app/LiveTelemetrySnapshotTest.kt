package com.pulseshift.app

import com.pulseshift.app.domain.model.CanHealthState
import com.pulseshift.app.domain.model.HealthFlags
import com.pulseshift.app.domain.model.LiveTelemetrySnapshot
import com.pulseshift.app.domain.model.RpmStatusFlags
import com.pulseshift.app.domain.model.RpmTelemetryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTelemetrySnapshotTest {
    private fun rpm(at: Long, fresh: Boolean = true, raw: Int = 122, reserved: Int = 0) = RpmTelemetryState(
        protocolVersion = 1,
        statusFlags = RpmStatusFlags(
            isCandidateFresh = fresh,
            isCalibrationVerified = false,
            isProductionRpmMappingEnabled = true,
            isPhysicalShiftLightsEnabled = false,
            isCanHealthy = true,
            isRecentCanTrafficDetected = true,
            isFlashRecordingActive = false,
            isPreviousCapturePreserved = true
        ),
        sequence = 9,
        uptimeMs = 66_000,
        provisionalRpm = 1480,
        rawCandidateCanValue = raw,
        captureStage = 1,
        totalCanFramesReceived = 1234,
        rpmSampleAgeMs = 42,
        reserved = reserved,
        timestampReceivedMs = at
    )

    private fun health(at: Long) = CanHealthState(
        version = 1,
        healthFlags = HealthFlags(
            isLittleFsMounted = true,
            isStorageFull = false,
            isReceivePerformanceDegraded = true,
            isBleClientConnected = true
        ),
        sequence = 5,
        driverMissedFrames = 2,
        hardwareFifoOverruns = 3,
        canBusErrors = 4,
        recordedFramesInCurrentSession = 12,
        timestampReceivedMs = at
    )

    @Test
    fun freshPacketsExposeOnlyActualExistingBleMeasurements() {
        val snapshot = LiveTelemetrySnapshot.from(
            telemetry = rpm(10_000),
            health = health(10_000),
            connected = true,
            nowMs = 10_150
        )
        assertTrue(snapshot.hasLiveData)
        assertEquals(1234L, snapshot.receivedFrames)
        assertEquals(66L, snapshot.uptimeSeconds)
        assertEquals(122, snapshot.candidateRawByte)
        assertEquals(42, snapshot.candidateAgeMs)
        assertEquals(1480, snapshot.provisionalRpm)
        assertEquals(2L, snapshot.missedFrames)
        assertEquals(3L, snapshot.fifoOverruns)
        assertEquals(4L, snapshot.busErrors)
        assertEquals(12L, snapshot.recordedThisSession)
        assertEquals(true, snapshot.storageMounted)
        assertEquals(true, snapshot.receiveDegraded)
        assertEquals(true, snapshot.capturePreserved)
        assertEquals(false, snapshot.recordingActive)
    }

    @Test
    fun physicalSensorUnitsNeverInventedFromCanCandidate() {
        // 0x7A (122) is an archival voltage *hypothesis*, not a volt reading.
        val snapshot = LiveTelemetrySnapshot.from(
            rpm(10_000, raw = 0x7A), health(10_000), true, nowMs = 10_200
        )
        assertEquals(0x7A, snapshot.candidateRawByte)
        assertNull(snapshot.batteryVolts)
        assertNull(snapshot.temperatureCelsius)
    }

    @Test
    fun disconnectErasesOldReadoutsImmediately() {
        val snapshot = LiveTelemetrySnapshot.from(
            rpm(10_000), health(10_000), false, nowMs = 10_050
        )
        assertFalse(snapshot.hasLiveData)
        assertNull(snapshot.receivedFrames)
        assertNull(snapshot.candidateRawByte)
        assertNull(snapshot.missedFrames)
        assertNull(snapshot.storageMounted)
    }

    @Test
    fun expiredNotificationsAreNotPresentedAsLive() {
        val snapshot = LiveTelemetrySnapshot.from(
            rpm(10_000), health(10_000), true, nowMs = 20_000
        )
        assertFalse(snapshot.hasLiveData)
        assertNull(snapshot.receivedFrames)
        assertNull(snapshot.missedFrames)
        assertNull(snapshot.trafficActive)
    }

    @Test
    fun eachNotificationSourceExpiresIndependently() {
        val snapshot = LiveTelemetrySnapshot.from(
            rpm(10_000), health(12_000), true, nowMs = 12_100
        )
        assertFalse(snapshot.hasFreshRpmPacket)
        assertTrue(snapshot.hasFreshHealthPacket)
        assertNull(snapshot.receivedFrames)
        assertEquals(3L, snapshot.fifoOverruns)
    }

    @Test
    fun absentCanCandidateDoesNotMasqueradeAsZeroRpm() {
        val snapshot = LiveTelemetrySnapshot.from(
            rpm(10_000, fresh = false), health(10_000), true, nowMs = 10_100
        )
        assertTrue(snapshot.hasFreshRpmPacket)
        assertEquals(1234L, snapshot.receivedFrames)
        assertNull(snapshot.candidateRawByte)
        assertNull(snapshot.provisionalRpm)
        assertNull(snapshot.candidateAgeMs)
    }

    @Test
    fun taggedCanVoltagePredictionIsDisplayedOnlyWhileFresh() {
        // 0xA5 = experiment-supported/fresh, 0x7A raw => 12.2 V hypothesis.
        val state = LiveTelemetrySnapshot.from(
            rpm(10_000, reserved = 0xA57A), health(10_000), true, nowMs = 10_100
        )
        assertEquals(true, state.voltageProbeSupported)
        assertEquals(0x7A, state.voltageCandidateRaw)
        assertEquals(12.2, state.batteryVolts!!, 0.00001)
        assertNull(state.temperatureCelsius)
    }

    @Test
    fun staleOrUnsupportedVoltageProbeNeverShowsZeroOrOldVoltage() {
        val stale = LiveTelemetrySnapshot.from(
            rpm(10_000, reserved = 0x5A00), health(10_000), true, nowMs = 10_100
        )
        assertTrue(stale.voltageProbeSupported)
        assertNull(stale.voltageCandidateRaw)
        assertNull(stale.batteryVolts)

        val legacy = LiveTelemetrySnapshot.from(
            rpm(10_000, reserved = 0), health(10_000), true, nowMs = 10_100
        )
        assertFalse(legacy.voltageProbeSupported)
        assertNull(legacy.batteryVolts)

        val disconnected = LiveTelemetrySnapshot.from(
            rpm(10_000, reserved = 0xA57A), health(10_000), false, nowMs = 10_100
        )
        assertNull(disconnected.batteryVolts)
        assertNull(disconnected.voltageCandidateRaw)

        val expired = LiveTelemetrySnapshot.from(
            rpm(10_000, reserved = 0xA57A), health(10_000), true, nowMs = 12_100
        )
        assertNull(expired.batteryVolts)
    }

    @Test
    fun timestampFromFutureIsNotTreatedAsFresh() {
        val snapshot = LiveTelemetrySnapshot.from(
            rpm(10_500), health(10_500), true, nowMs = 10_000
        )
        assertFalse(snapshot.hasLiveData)
    }
}
