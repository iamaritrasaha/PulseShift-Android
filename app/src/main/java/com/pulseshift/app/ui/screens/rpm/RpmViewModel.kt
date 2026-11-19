package com.pulseshift.app.ui.screens.rpm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseshift.app.data.repository.PulseShiftRepository
import com.pulseshift.app.domain.model.CalibrationFitResult
import com.pulseshift.app.domain.model.CalibrationSessionStatus
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.RpmTelemetryState
import com.pulseshift.app.domain.model.ShiftLightConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RpmViewModel(
    private val repository: PulseShiftRepository
) : ViewModel() {
    val connectionState = repository.connectionState
    val rpmTelemetryState: StateFlow<RpmTelemetryState?> = repository.rpmTelemetryState
    val configSnapshot: StateFlow<ConfigSnapshot?> = repository.configSnapshot
    val shiftLightConfig: StateFlow<ShiftLightConfig> = repository.shiftLightConfig
    val hasUnsavedChanges: StateFlow<Boolean> = repository.hasUnsavedChanges

    private val _calibrationStatus = MutableStateFlow<CalibrationSessionStatus?>(null)
    val calibrationStatus: StateFlow<CalibrationSessionStatus?> = _calibrationStatus.asStateFlow()

    private val _calibrationFitResult = MutableStateFlow<CalibrationFitResult?>(null)
    val calibrationFitResult: StateFlow<CalibrationFitResult?> = _calibrationFitResult.asStateFlow()

    fun applyDecoderConfiguration(canId: Long, byteIndex: Int, isExtended: Boolean, multiplier: Float) {
        viewModelScope.launch {
            repository.applyDecoderConfiguration(canId, byteIndex, isExtended, multiplier)
        }
    }

    fun setRpmHysteresis(hysteresisRpm: Int) {
        viewModelScope.launch {
            repository.setRpmHysteresis(hysteresisRpm)
        }
    }

    fun updateThreshold(index: Int, rpm: Int) {
        if (index in shiftLightConfig.value.thresholds.indices) {
            repository.queueLightingEdit("RPM stage ${index + 1}") { repository.setLedThreshold(index, rpm) }
        }
    }

    fun saveToController() {
        viewModelScope.launch {
            repository.saveConfigurationToController()
        }
    }

    // --- Firmware-driven calibration session ---

    fun refreshCalibrationStatus() {
        viewModelScope.launch {
            _calibrationStatus.value = repository.queryCalibrationStatus()
        }
    }

    fun beginCalibrationSession() {
        viewModelScope.launch {
            repository.beginCalibrationSession()
            _calibrationStatus.value = repository.queryCalibrationStatus()
        }
    }

    fun captureCalibrationPoint(refRpm: Int) {
        viewModelScope.launch {
            if (!repository.startCalibrationCapture()) {
                _calibrationStatus.value = repository.queryCalibrationStatus()
                return@launch
            }
            // Poll the real capture window (CAL_CAPTURE_STATUS gives samples
            // and elapsed ms) instead of a blind fixed sleep; commit only
            // when the window has actually closed.
            val deadline = System.currentTimeMillis() + 5000L
            while (System.currentTimeMillis() < deadline) {
                delay(200)
                val status = repository.queryCalibrationStatus() ?: break
                if (!status.captureActive) break
            }
            repository.commitCalibrationPoint(refRpm)
            _calibrationStatus.value = repository.queryCalibrationStatus()
        }
    }

    fun computeCalibration(apply: Boolean) {
        viewModelScope.launch {
            _calibrationFitResult.value = repository.computeCalibration(apply)
            _calibrationStatus.value = repository.queryCalibrationStatus()
        }
    }

    fun abortCalibrationSession() {
        viewModelScope.launch {
            repository.abortCalibrationSession()
            _calibrationStatus.value = repository.queryCalibrationStatus()
        }
    }
}
