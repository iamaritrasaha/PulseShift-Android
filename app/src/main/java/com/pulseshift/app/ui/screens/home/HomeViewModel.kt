package com.pulseshift.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseshift.app.data.repository.PulseShiftRepository
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.CanHealthState
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.RpmTelemetryState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: PulseShiftRepository
) : ViewModel() {
    val lightingState: StateFlow<LightingState> = repository.lightingState
    val connectedDevice: StateFlow<BleDevice?> = repository.connectedDevice
    val presets: StateFlow<List<Preset>> = repository.presets
    val rpmTelemetryState: StateFlow<RpmTelemetryState?> = repository.rpmTelemetryState
    val canHealthState: StateFlow<CanHealthState?> = repository.canHealthState
    val hasUnsavedChanges: StateFlow<Boolean> = repository.hasUnsavedChanges

    fun togglePower() {
        viewModelScope.launch {
            repository.setPower(!lightingState.value.isPowerOn)
        }
    }

    fun updateBrightness(brightness: Int) {
        viewModelScope.launch {
            repository.setBrightness(brightness)
        }
    }

    fun applyPreset(preset: Preset) {
        viewModelScope.launch {
            repository.applyPreset(preset)
        }
    }

    fun saveToController() {
        viewModelScope.launch {
            repository.saveConfigurationToController()
        }
    }
}
