package com.pulseshift.app

import com.pulseshift.app.domain.model.CaptureRecord
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.DiagnosticsReport
import com.pulseshift.app.domain.model.RpmStatusFlags
import com.pulseshift.app.domain.model.RpmTelemetryState
import com.pulseshift.app.ui.screens.terminal.TerminalCommandRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/** Formatter contract tests for the terminal registry (device-truth only). */
class TerminalRegistryTest {

    private fun freshRpm(flagByte: Int): RpmTelemetryState {
        return RpmTelemetryState(
            protocolVersion = 1,
            statusFlags = RpmStatusFlags.fromByte(flagByte.toByte()),
            sequence = 1,
            uptimeMs = 61000,
            provisionalRpm = 1480,
            rawCandidateCanValue = 37,
            captureStage = 2,
            totalCanFramesReceived = 22542,
            rpmSampleAgeMs = 30
        )
    }

    @Test
    fun `rpm status prints UNVERIFIED label and live values`() {
        val text = TerminalCommandRegistry.formatRpmStatus(freshRpm(0x01), null)
        assertTrue(text.contains("UNVERIFIED"))
        assertTrue(text.contains("1480 RPM"))
        assertTrue(text.contains("Raw value    37"))
        assertTrue(text.contains("Shift lights NOT RENDERING"))
    }

    @Test
    fun `rpm status prints configured CAN candidate ID when present`() {
        val text = TerminalCommandRegistry.formatRpmStatus(freshRpm(0x01), null, configuredCanId = 0x280)
        assertTrue(text.contains("CAN ID       0x280 (configured)"))
    }

    @Test
    fun `rpm status prints VERIFIED and rendering when flags set`() {
        // bit3 (SHIFT_LIGHTS_ENABLED) set alongside fresh + verified.
        val text = TerminalCommandRegistry.formatRpmStatus(freshRpm(0x0B), null)
        assertTrue(text.contains("VERIFIED"))
        assertTrue(text.contains("Shift lights RENDERING"))
    }

    @Test
    fun `diagnostics formatting keeps CAN and BLE scopes separated`() {
        val report = DiagnosticsReport(
            snapshotId = 1,
            uptimeMs = 90_000,
            firmwareVersion = "2.2.0",
            canHealthy = true,
            canTrafficActive = true,
            canTotalRx = 22542,
            canAccepted = 22300,
            canMissed = 12,
            canOverrun = 0,
            canBusErrors = 3,
            bleConnected = true,
            ownerEnrolled = true,
            sessionAuthorized = true,
            storageMounted = true
        )
        val can = TerminalCommandRegistry.formatDiagnostics(report, "CAN")
        assertTrue(can.contains("rx=22542"))
        assertTrue(can.contains("busErr=3"))
        assertTrue(can.contains("UNVERIFIED"))

        val ble = TerminalCommandRegistry.formatDiagnostics(report, "BLE")
        assertTrue(ble.contains("sessionAuthorized=true"))
        assertTrue(!ble.contains("rx=22542"))
    }

    @Test
    fun `capture record formatter renders id dlc and hex bytes`() {
        val line = TerminalCommandRegistry.formatCaptureRecord(
            CaptureRecord(
                timestampMs = 61234,
                stage = 2,
                canId = 0x301,
                isExtended = false,
                dlc = 8,
                dataHex = "8102B90D0FD3C900"
            )
        )
        assertTrue(line.contains("id=0x301"))
        assertTrue(line.contains("dlc=8"))
        assertTrue(line.contains("B9 0D"))
    }

    @Test
    fun `snapshot formatter exposes calibration state`() {
        val snapshot = ConfigSnapshot(
            snapshotId = 1,
            activeRevision = 42,
            isPowerOn = true,
            brightness = 200,
            effect = com.pulseshift.app.domain.model.EffectType.PROVISIONAL_PREVIEW,
            calibrationVerified = false
        )
        val text = TerminalCommandRegistry.formatSnapshot(snapshot)
        assertTrue(text.contains("Provisional"))
        assertTrue(text.contains("UNVERIFIED"))
        assertTrue(text.contains("42"))
        assertTrue(text.contains("0x301"))
    }
}
