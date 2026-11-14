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
import kotlinx.coroutines.launch

class RpmViewModel(
    private val repository: PulseShiftRepository
) : ViewModel() {
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
        viewModelScope.launch {
            val current = shiftLightConfig.value.thresholds.toMutableList()
            if (index in current.indices) {
                current[index] = rpm
                repository.updateShiftLightConfig(shiftLightConfig.value.copy(thresholds = current))
            }
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
            repository.startCalibrationCapture()
            // The firmware samples raw values for 2 seconds; wait out the
            // window before committing the reference point.
            kotlinx.coroutines.delay(2100)
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
