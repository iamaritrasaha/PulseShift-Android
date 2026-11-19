package com.pulseshift.app.ui.screens.lighting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseshift.app.data.repository.PulseShiftRepository
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.ConfigAckResponse
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.ProfileApplyResult
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.RpmTelemetryState
import com.pulseshift.app.domain.model.ShiftLightConfig
import com.pulseshift.app.domain.model.StartupAnimation
import com.pulseshift.app.domain.model.WarningStyle
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
    val isNvsConfirmed: StateFlow<Boolean> = repository.isNvsConfirmed
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

    fun selectLed(index: Int) {
        _selectedLedIndex.value = index
    }

    fun setPower(power: Boolean) =
        repository.queueLightingEdit("power") { repository.setPower(power) }

    fun setColor(color: RgbColor) {
        val zone = _selectedLedIndex.value
        repository.queueLightingEdit(if (zone < 0) "all colors" else "zone ${zone + 1} color") {
            if (zone < 0) repository.setGlobalColor(color)
            else repository.setIndividualLedColor(zone, color)
        }
    }

    fun setEffect(effect: EffectType) =
        repository.queueLightingEdit("effect") { repository.setEffect(effect) }

    fun setEffectSpeed(speed: Int) =
        repository.queueLightingEdit("effect speed") { repository.setEffectSpeed(speed) }

    fun setBrightness(brightness: Int) =
        repository.queueLightingEdit("brightness") { repository.setBrightness(brightness) }

    fun setLedDirection(direction: Int) =
        repository.queueLightingEdit("direction") { repository.setLedDirection(direction) }

    fun setStartupAnimation(animation: StartupAnimation, durationMs: Int) =
        repository.queueLightingEdit("startup animation") { repository.setStartupAnimation(animation, durationMs) }

    fun updateThreshold(index: Int, newRpm: Int) =
        repository.queueLightingEdit("RPM stage ${index + 1}") { repository.setLedThreshold(index, newRpm) }

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
            try {
                val preset = repository.saveCurrentSetupAsPreset(name)
                if (preset != null) {
                    _actionFeedback.tryEmit("Saved '${preset.name}' to this phone")
                }
            } catch (error: IllegalStateException) {
                _actionFeedback.tryEmit(error.message ?: "Could not save preset to phone")
            }
        }
    }

    fun setShiftWarning(warningRpm: Int, style: WarningStyle, warningColor: RgbColor) =
        repository.queueLightingEdit("shift warning") {
            repository.setShiftWarning(warningRpm, style.id, warningColor)
        }

    fun saveToController() {
        viewModelScope.launch {
            if (_isBusy.value) return@launch
            _isBusy.value = true
            try {
                if (repository.saveConfigurationToController()) {
                    _actionFeedback.tryEmit("ESP32 flash readback verified")
                } else {
                    _actionFeedback.tryEmit("Flash save unconfirmed. Check the controller message and retry.")
                }
            } finally {
                _isBusy.value = false
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
                val result = repository.applyPreset(preset)
                _actionFeedback.tryEmit(result.message(preset.name))
            } catch (e: IllegalStateException) {
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
