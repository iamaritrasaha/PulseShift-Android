package com.pulseshift.app.ui.screens.lighting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseshift.app.data.repository.PulseShiftRepository
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.ConfigAckResponse
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.RpmTelemetryState
import com.pulseshift.app.domain.model.ShiftLightConfig
import com.pulseshift.app.domain.model.StartupAnimation
import com.pulseshift.app.domain.model.WarningStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class LightingViewModel(
    private val repository: PulseShiftRepository
) : ViewModel() {
    val lightingState: StateFlow<LightingState> = repository.lightingState
    val shiftLightConfig: StateFlow<ShiftLightConfig> = repository.shiftLightConfig
    val configSnapshot: StateFlow<ConfigSnapshot?> = repository.configSnapshot
    val hasUnsavedChanges: StateFlow<Boolean> = repository.hasUnsavedChanges
    val lastAckResponse: SharedFlow<ConfigAckResponse> = repository.lastAckResponse
    val rpmTelemetryState: StateFlow<RpmTelemetryState?> = repository.rpmTelemetryState
    val connectedDevice: StateFlow<BleDevice?> = repository.connectedDevice
    val presets: StateFlow<List<com.pulseshift.app.domain.model.Preset>> = repository.presets

    // Selected LED: -1 means "All LEDs", 0-5 means single LED
    private val _selectedLedIndex = MutableStateFlow(-1)
    val selectedLedIndex: StateFlow<Int> = _selectedLedIndex.asStateFlow()

    private var brightnessThrottleJob: Job? = null
    private var speedThrottleJob: Job? = null
    private var colorThrottleJob: Job? = null

    fun selectLed(index: Int) {
        _selectedLedIndex.value = index
    }

    fun setPower(power: Boolean) {
        viewModelScope.launch {
            repository.setPower(power)
        }
    }

    fun setColor(color: RgbColor) {
        colorThrottleJob?.cancel()
        colorThrottleJob = viewModelScope.launch {
            delay(50)
            if (_selectedLedIndex.value == -1) {
                repository.setGlobalColor(color)
            } else {
                repository.setIndividualLedColor(_selectedLedIndex.value, color)
            }
        }
    }

    fun setEffect(effect: EffectType) {
        viewModelScope.launch {
            repository.setEffect(effect)
        }
    }

    fun setEffectSpeed(speed: Int) {
        speedThrottleJob?.cancel()
        speedThrottleJob = viewModelScope.launch {
            delay(50)
            repository.setEffectSpeed(speed)
        }
    }

    fun setBrightness(brightness: Int) {
        brightnessThrottleJob?.cancel()
        brightnessThrottleJob = viewModelScope.launch {
            delay(50)
            repository.setBrightness(brightness)
        }
    }

    fun setLedDirection(direction: Int) {
        viewModelScope.launch {
            repository.setLedDirection(direction)
        }
    }

    fun setStartupAnimation(animation: StartupAnimation, durationMs: Int) {
        viewModelScope.launch {
            repository.setStartupAnimation(animation, durationMs)
        }
    }

    fun updateThreshold(index: Int, newRpm: Int) {
        val currentThresholds = shiftLightConfig.value.thresholds.toMutableList()
        if (index in 0..5) {
            currentThresholds[index] = newRpm
            val updated = shiftLightConfig.value.copy(thresholds = currentThresholds)
            viewModelScope.launch {
                repository.updateShiftLightConfig(updated)
            }
        }
    }

    fun setShiftWarning(warningRpm: Int, style: WarningStyle, warningColor: RgbColor) {
        val updatedColors = shiftLightConfig.value.colors.toMutableList()
        if (updatedColors.size >= 6) {
            updatedColors[5] = warningColor
        }
        val updated = shiftLightConfig.value.copy(
            warningRpm = warningRpm,
            warningStyle = style,
            colors = updatedColors
        )
        viewModelScope.launch {
            repository.updateShiftLightConfig(updated)
            repository.setWarningRgb(warningColor)
        }
    }



    fun saveToController() {
        viewModelScope.launch {
            repository.saveConfigurationToController()
        }
    }

    fun reloadFromController() {
        viewModelScope.launch {
            repository.reloadConfigFromController()
        }
    }

    fun restoreControllerDefaults() {
        viewModelScope.launch {
            repository.restoreDefaultControllerConfig()
        }
    }

    fun applyPreset(preset: com.pulseshift.app.domain.model.Preset) {
        viewModelScope.launch {
            repository.applyPreset(preset)
        }
    }

    fun savePreset(preset: com.pulseshift.app.domain.model.Preset) {
        viewModelScope.launch {
            repository.savePreset(preset)
        }
    }

    fun deletePreset(id: String) {
        viewModelScope.launch {
            repository.deletePreset(id)
        }
    }

    fun restoreDefaultPresets() {
        viewModelScope.launch {
            repository.restoreDefaultPresets()
        }
    }
}
