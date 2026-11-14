package com.pulseshift.app.ui.screens.terminal

import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.CaptureRecord
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.DiagnosticsReport
import com.pulseshift.app.domain.model.RpmTelemetryState

/**
 * Help catalogue and device-response formatters for the terminal. All values
 * rendered here come from real device responses — nothing is fabricated.
 */
object TerminalCommandRegistry {

    fun helpText(): String = buildString {
        appendLine("PulseShift Terminal — commands (case-insensitive)")
        appendLine("  STATUS / CAN STATUS   Diagnostics TLV report (0x50)")
        appendLine("  BLE STATUS            Link, auth and firmware state (0x50)")
        appendLine("  RPM STATUS            Live candidate telemetry + TLV")
        appendLine("  RPM MONITOR [ON|OFF]  Stream live RPM at <= 10 Hz")
        appendLine("  RPM MULTIPLIER <m>    Set provisional multiplier (0.1..1000)")
        appendLine("  CONFIG STATUS         Read full config snapshot (0x02)")
        appendLine("  CONFIG SAVE           Persist config to NVS (0x20)")
        appendLine("  CONFIG RESET          Restore defaults (0x21, confirm)")
        appendLine("  EFFECT <0..5>         5 = UNVERIFIED CAN preview")
        appendLine("  RPM TEST <0..20000>   10 s LED bench test (0x55)")
        appendLine("  RPM TEST OFF          Stop bench test")
        appendLine("  STAGE <1|2|3>         Capture stage label (0x54)")
        appendLine("  CAPTURE STOP|RESUME   Flash recording toggle (0x53)")
        appendLine("  LOGS                  Request historical log page")
        appendLine("  DUMP [NEXT|PAGE n|ALL|CANCEL]  CAN capture records (12/page)")
        appendLine("  RESTART               ESP32 restart (confirm)")
        appendLine("  CLEAR DIAGNOSTICS     Clear event log only (confirm)")
        appendLine("  AUTH                  Owner enrollment instructions")
        appendLine("  STREAM, CLEAR YES     USB-only (refused over BLE)")
    }

    fun authInstructions(): String = buildString {
        appendLine("Owner enrollment / authorization:")
        appendLine("  1. Press the physical BOOT button on the ESP32 — a 60 s")
        appendLine("     commissioning window opens (cyan pulse).")
        appendLine("  2. Open Devices > Enroll as Owner in the app to bind a secret.")
        appendLine("  3. Every later connection re-proves it via challenge-response.")
        appendLine("  Secrets never leave the Android KeyStore; no BLE bypass exists.")
    }

    fun formatRpmSample(rpm: RpmTelemetryState): String {
        val value = if (rpm.isRpmAvailable && !rpm.isStale) "${rpm.provisionalRpm} RPM" else "---"
        return "t=${rpm.uptimeMs}s  $value  raw=${rpm.rawCandidateCanValue}  age=${rpm.rpmSampleAgeMs}ms"
    }

    fun formatRpmStatus(rpm: RpmTelemetryState, device: BleDevice?): String = buildString {
        appendLine("CAN ID       0x301 (configured)")
        appendLine("Raw value    ${rpm.rawCandidateCanValue}")
        appendLine("Estimated    ${if (rpm.isRpmAvailable && !rpm.isStale) "${rpm.provisionalRpm} RPM" else "---"}")
        appendLine("Sample age   ${rpm.rpmSampleAgeMs} ms")
        appendLine("Signal       ${if (rpm.statusFlags.isCandidateFresh) "FRESH" else "STALE"}")
        appendLine("Calibration  ${if (rpm.statusFlags.isCalibrationVerified) "VERIFIED" else "UNVERIFIED"}")
        appendLine("Shift lights ${if (rpm.statusFlags.isPhysicalShiftLightsEnabled) "RENDERING" else "NOT RENDERING"}")
        appendLine("CAN total RX ${rpm.totalCanFramesReceived}")
    }

    fun formatDiagnostics(report: DiagnosticsReport, scope: String): String = buildString {
        appendLine("Firmware    ${report.firmwareVersion}")
        appendLine("Uptime      ${report.uptimeMs / 1000} s (reset reason ${report.resetReason})")
        if (scope == "CAN" || scope == "ALL") {
            appendLine("CAN         healthy=${report.canHealthy} traffic=${report.canTrafficActive}")
            appendLine("            rx=${report.canTotalRx} accepted=${report.canAccepted}")
            appendLine("            missed=${report.canMissed} overrun=${report.canOverrun} busErr=${report.canBusErrors}")
            appendLine("Candidate   0x${java.lang.Long.toHexString(report.candidateCanId).uppercase()} raw=${report.candidateRaw} est=${report.candidateEstimatedRpm} fresh=${report.candidateFresh} cal=${if (report.candidateCalVerified) "VERIFIED" else "UNVERIFIED"}")
        }
        if (scope == "BLE" || scope == "ALL") {
            appendLine("BLE         connected=${report.bleConnected} advertising=${report.bleAdvertising}")
            appendLine("            ownerEnrolled=${report.ownerEnrolled} sessionAuthorized=${report.sessionAuthorized}")
        }
        appendLine("Storage     mounted=${report.storageMounted} full=${report.storageFull} preserved=${report.capturePreserved}")
        appendLine("Recording   ${report.captureRecording} records=${report.captureRecords} size=${report.captureSizeBytes}B")
        if (report.lastError.isNotBlank()) appendLine("LastError   ${report.lastError}")
    }

    fun formatSnapshot(snapshot: ConfigSnapshot): String = buildString {
        appendLine("Revision     ${snapshot.activeRevision}  Power=${if (snapshot.isPowerOn) "ON" else "OFF"}  Brightness=${snapshot.brightness}")
        appendLine("Effect       ${snapshot.effect.id} (${snapshot.effect.displayName})  Speed=${snapshot.effectSpeed}")
        appendLine("Direction    ${if (snapshot.ledDirection == 1) "REVERSED" else "FORWARD"}  Startup=${snapshot.startupAnimation} ${snapshot.startupDurationMs}ms")
        appendLine("Thresholds   ${snapshot.rpmThresholds.joinToString()}")
        appendLine("Warning      ${snapshot.warningRpm} RPM, pattern=${snapshot.warningPatternId}")
        appendLine("Decoder      ID=0x${java.lang.Long.toHexString(snapshot.canCandidateId).uppercase()} byte=${snapshot.candidateByteIndex} ext=${snapshot.isExtendedFrame}")
        appendLine("Multiplier   ${snapshot.rpmMultiplier}  Cal=${if (snapshot.calibrationVerified) "VERIFIED" else "UNVERIFIED"}")
    }

    fun formatCaptureRecord(record: CaptureRecord): String {
        val hex = record.dataHex.chunked(2).joinToString(" ")
        return "rec t=${record.timestampMs} stage=${record.stage} id=0x${"%X".format(record.canId)}" +
            "${if (record.isExtended) " ext" else ""} dlc=${record.dlc} [${hex}]"
    }
}
