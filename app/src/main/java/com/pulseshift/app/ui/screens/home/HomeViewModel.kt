package com.pulseshift.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.data.repository.PulseShiftRepository
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.RpmTelemetryState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: PulseShiftRepository
) : ViewModel() {
    val lightingState: StateFlow<LightingState> = repository.lightingState
    val connectedDevice: StateFlow<BleDevice?> = repository.connectedDevice
    val presets: StateFlow<List<Preset>> = repository.presets
    val rpmTelemetryState: StateFlow<RpmTelemetryState?> = repository.rpmTelemetryState
    val hasUnsavedChanges: StateFlow<Boolean> = repository.hasUnsavedChanges
    val connectionState: StateFlow<BleConnectionState> = repository.connectionState
    val connectionError: StateFlow<String?> = repository.connectionError
    val isReconnecting: StateFlow<Boolean> = repository.isReconnecting
    val isAuthenticated: StateFlow<Boolean> = repository.isAuthenticated
    val lightingSyncError: kotlinx.coroutines.flow.SharedFlow<String> = repository.lightingSyncError

    /** Debounced brightness write: drag gestures must not flood the GATT queue. */
    private val brightnessInput = MutableStateFlow<Int?>(null)

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            brightnessInput.debounce(120).collect { value ->
                if (value != null) {
                    repository.setBrightness(value)
                    brightnessInput.value = null
                }
            }
        }
    }

    fun updateBrightness(brightness: Int) {
        brightnessInput.value = brightness
    }

    fun togglePower() {
        viewModelScope.launch {
            repository.setPower(!lightingState.value.isPowerOn)
        }
    }

    fun applyPreset(preset: Preset) {
        viewModelScope.launch {
            repository.applyPreset(preset)
        }
    }
}
