package com.pulseshift.app.ui.screens.lighting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseshift.app.data.repository.PulseShiftRepository
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.ConfigAckResponse
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.ProfileApplyResult
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
    val lightingSyncError: SharedFlow<String> = repository.lightingSyncError
    val rpmTelemetryState: StateFlow<RpmTelemetryState?> = repository.rpmTelemetryState
    val connectedDevice: StateFlow<BleDevice?> = repository.connectedDevice
    val presets: StateFlow<List<com.pulseshift.app.domain.model.Preset>> = repository.presets

    // Selected LED: -1 means "All LEDs", 0-5 means single LED
    private val _selectedLedIndex = MutableStateFlow(-1)
    val selectedLedIndex: StateFlow<Int> = _selectedLedIndex.asStateFlow()

    val authState: StateFlow<com.pulseshift.app.domain.model.AuthState> = repository.authState
    val isAuthenticated: StateFlow<Boolean> = repository.isAuthenticated

    private val _actionFeedback = kotlinx.coroutines.flow.MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    val actionFeedback: SharedFlow<String> = _actionFeedback

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private var brightnessThrottleJob: Job? = null
    private var speedThrottleJob: Job? = null
    private var colorThrottleJob: Job? = null
    private var startupThrottleJob: Job? = null
    private val thresholdJobs = Array<Job?>(6) { null }

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
            delay(120) // Paced to avoid overflowing ESP32 4-slot ring buffer
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
            delay(120)
            repository.setEffectSpeed(speed)
        }
    }

    fun setBrightness(brightness: Int) {
        brightnessThrottleJob?.cancel()
        brightnessThrottleJob = viewModelScope.launch {
            delay(120)
            repository.setBrightness(brightness)
        }
    }

    fun setLedDirection(direction: Int) {
        viewModelScope.launch {
            repository.setLedDirection(direction)
        }
    }

    fun setStartupAnimation(animation: StartupAnimation, durationMs: Int) {
        startupThrottleJob?.cancel()
        startupThrottleJob = viewModelScope.launch {
            delay(120)
            repository.setStartupAnimation(animation, durationMs)
        }
    }

    fun updateThreshold(index: Int, newRpm: Int) {
        thresholdJobs.getOrNull(index)?.cancel()
        if (index in 0..5) {
            thresholdJobs[index] = viewModelScope.launch {
                delay(120)
                repository.setLedThreshold(index, newRpm)
            }
        }
    }

    fun apply1000To6000Profile() {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                when (repository.apply1000To6000Profile()) {
                    ProfileApplyResult.PERSISTED -> {
                        _actionFeedback.tryEmit("1,000–6,000 RPM profile applied and saved to ESP32 Flash (NVS)")
                    }
                    ProfileApplyResult.APPLIED_ONLY -> {
                        _actionFeedback.tryEmit("1,000–6,000 RPM profile active in RAM only (NVS save unconfirmed)")
                    }
                    ProfileApplyResult.FAILED -> {
                        _actionFeedback.tryEmit("Failed to apply 1,000–6,000 RPM profile (check connection/auth)")
                    }
                }
            } catch (e: Exception) {
                _actionFeedback.tryEmit("Error applying profile: ${e.message}")
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun saveCurrentSetupAsPreset(name: String) {
        viewModelScope.launch {
            val preset = repository.saveCurrentSetupAsPreset(name)
            if (preset != null) {
                _actionFeedback.tryEmit("Saved current setup as '${preset.name}'")
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
            _isBusy.value = true
            val ok = repository.saveConfigurationToController()
            _isBusy.value = false
            if (ok) {
                _actionFeedback.tryEmit("Configuration saved to ESP32 flash (NVS)")
            } else {
                _actionFeedback.tryEmit("Failed to save to ESP32 flash (check owner auth)")
            }
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
            try {
                repository.applyPreset(preset)
                _actionFeedback.tryEmit("Applied preset '${preset.name}'")
            } catch (e: Exception) {
                _actionFeedback.tryEmit("Failed to apply preset: ${e.message}")
            }
        }
    }

    fun savePreset(preset: com.pulseshift.app.domain.model.Preset) {
        viewModelScope.launch {
            try {
                repository.savePreset(preset)
                _actionFeedback.tryEmit("Saved preset '${preset.name}'")
            } catch (e: Exception) {
                _actionFeedback.tryEmit("Failed to save preset: ${e.message}")
            }
        }
    }

    fun deletePreset(id: String) {
        viewModelScope.launch {
            try {
                repository.deletePreset(id)
                _actionFeedback.tryEmit("Preset deleted")
            } catch (e: Exception) {
                _actionFeedback.tryEmit("Failed to delete preset: ${e.message}")
            }
        }
    }

    fun restoreDefaultPresets() {
        viewModelScope.launch {
            try {
                repository.restoreDefaultPresets()
                _actionFeedback.tryEmit("Default presets restored")
            } catch (e: Exception) {
                _actionFeedback.tryEmit("Failed to restore default presets: ${e.message}")
            }
        }
    }
}
