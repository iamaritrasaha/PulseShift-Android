package com.pulseshift.app.data.repository

import com.pulseshift.app.domain.model.AuthState
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.CanHealthState
import com.pulseshift.app.domain.model.CalibrationFitResult
import com.pulseshift.app.domain.model.CalibrationSessionStatus
import com.pulseshift.app.domain.model.CaptureExportState
import com.pulseshift.app.domain.model.ConfigAckResponse
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.DeviceCommandOutcome
import com.pulseshift.app.domain.model.DiagnosticLogMessage
import com.pulseshift.app.domain.model.DiagnosticsReport
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.HistoricalLogLoadingState
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.ProfileApplyResult
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.RpmTelemetryState
import com.pulseshift.app.domain.model.ShiftLightConfig
import com.pulseshift.app.domain.model.StartupAnimation
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface PulseShiftRepository {
    val lightingState: StateFlow<LightingState>
    val connectedDevice: StateFlow<BleDevice?>
    val presets: StateFlow<List<Preset>>
    val shiftLightConfig: StateFlow<ShiftLightConfig>
    val configSnapshot: StateFlow<ConfigSnapshot?>
    val hasUnsavedChanges: StateFlow<Boolean>
    val lastAckResponse: SharedFlow<ConfigAckResponse>

    // Transport state (wrapped from the BLE manager so screens have one source)
    val connectionState: StateFlow<com.pulseshift.app.data.ble.BleConnectionState>
    val connectionError: StateFlow<String?>
    val isReconnecting: StateFlow<Boolean>

    /** Failures of live lighting writes (ACK timeouts, rejections, auth). */
    val lightingSyncError: SharedFlow<String>

    // Telemetry & Diagnostic Protocol V1
    val rpmTelemetryState: StateFlow<RpmTelemetryState?>
    val canHealthState: StateFlow<CanHealthState?>
    val diagnosticLogMessages: StateFlow<List<DiagnosticLogMessage>>
    val historicalPageLoadingState: StateFlow<HistoricalLogLoadingState>

    // Owner authentication (enrollment requires the physical BOOT window)
    val authState: StateFlow<AuthState>
    val isAuthenticated: StateFlow<Boolean>
    suspend fun enrollOwner(secret: ByteArray): Boolean
    suspend fun authenticateChallengeResponse(): Boolean

    // Configuration Controls
    suspend fun setPower(power: Boolean)
    suspend fun setBrightness(brightness: Int)
    suspend fun setEffect(effect: EffectType): Boolean
    suspend fun setEffectSpeed(speed: Int)
    suspend fun setGlobalColor(color: RgbColor)
    suspend fun setIndividualLedColor(index: Int, color: RgbColor)
    suspend fun setAllLedColors(colors: List<RgbColor>)
    suspend fun setStartupAnimation(animation: StartupAnimation, durationMs: Int)
    suspend fun setLedDirection(direction: Int)
    suspend fun setWarningRgb(color: RgbColor)
    suspend fun updateShiftLightConfig(config: ShiftLightConfig)
    /** Write ONE LED threshold (opcode 0x16) with ACK verification. */
    suspend fun setLedThreshold(index: Int, rpm: Int)
    suspend fun applyDecoderConfiguration(canId: Long, byteIndex: Int, isExtended: Boolean, multiplier: Float)
    suspend fun setRpmHysteresis(hysteresisRpm: Int)

    // Presets
    suspend fun applyPreset(preset: Preset)
    suspend fun savePreset(preset: Preset)
    suspend fun updatePreset(preset: Preset)
    suspend fun duplicatePreset(preset: Preset): Preset?
    suspend fun saveCurrentSetupAsPreset(name: String): Preset?
    suspend fun deletePreset(id: String)
    suspend fun restoreDefaultPresets()

    // Rapid Profile Application
    suspend fun apply1000To6000Profile(): ProfileApplyResult

    // Transactions & Non-Volatile Persistence
    suspend fun saveConfigurationToController(): Boolean
    suspend fun reloadConfigFromController(): ConfigSnapshot?
    suspend fun restoreDefaultControllerConfig()

    // Log Control
    suspend fun requestHistoricalLogPage(startOffset: Long)
    suspend fun cancelHistoricalLogRetrieval()
    suspend fun clearDiagnosticLogs()
    fun exportLogsAsText(): String
    fun exportLogsAsCsv(): String

    // Device command surface (Diagnostics / Advanced Commands)
    val diagnosticsReport: StateFlow<DiagnosticsReport?>
    val captureExportState: StateFlow<CaptureExportState>
    val deviceCommandHistory: StateFlow<List<DeviceCommandOutcome>>
    suspend fun requestDiagnosticsReport(): DiagnosticsReport?
    suspend fun reloadConfigurationFromDevice(): Boolean
    suspend fun restartDevice(): Boolean
    suspend fun clearDeviceDiagnosticHistory(): Boolean
    suspend fun setCaptureRecording(enable: Boolean): Boolean
    suspend fun setCaptureStage(stage: Int): Boolean
    suspend fun setLedTestRpm(rpm: Int, enable: Boolean): Boolean
    /** Begin a bounded, cancellable capture export from [startRecord] (12-record pages). */
    fun startCaptureExport(startRecord: Long = 0L)
    fun cancelCaptureExport()

    // Firmware-driven calibration workflow
    suspend fun queryCalibrationStatus(): CalibrationSessionStatus?
    suspend fun beginCalibrationSession(): Boolean
    suspend fun startCalibrationCapture(): Boolean
    suspend fun commitCalibrationPoint(refRpm: Int): Boolean
    suspend fun computeCalibration(apply: Boolean): CalibrationFitResult?
    suspend fun abortCalibrationSession(): Boolean
}
