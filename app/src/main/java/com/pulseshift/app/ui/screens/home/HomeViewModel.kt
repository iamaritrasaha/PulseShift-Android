package com.pulseshift.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.data.repository.PulseShiftRepository
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.CanHealthState
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.RpmTelemetryState
import com.pulseshift.app.domain.model.ShiftLightConfig
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: PulseShiftRepository
) : ViewModel() {
    val lightingState: StateFlow<LightingState> = repository.lightingState
    val connectedDevice: StateFlow<BleDevice?> = repository.connectedDevice
    val shiftLightConfig: StateFlow<ShiftLightConfig> = repository.shiftLightConfig
    val presets: StateFlow<List<Preset>> = repository.presets
    val rpmTelemetryState: StateFlow<RpmTelemetryState?> = repository.rpmTelemetryState
    val canHealthState: StateFlow<CanHealthState?> = repository.canHealthState
    val hasUnsavedChanges: StateFlow<Boolean> = repository.hasUnsavedChanges
    val connectionState: StateFlow<BleConnectionState> = repository.connectionState
    val connectionError: StateFlow<String?> = repository.connectionError
    val isReconnecting: StateFlow<Boolean> = repository.isReconnecting
    val isAuthenticated: StateFlow<Boolean> = repository.isAuthenticated
    val lightingSyncError: kotlinx.coroutines.flow.SharedFlow<String> = repository.lightingSyncError

    fun updateBrightness(brightness: Int) =
        repository.queueLightingEdit("brightness") { repository.setBrightness(brightness) }

    fun togglePower() {
        val power = !lightingState.value.isPowerOn
        repository.queueLightingEdit("power") { repository.setPower(power) }
    }

    fun applyPreset(preset: Preset) {
        viewModelScope.launch {
            try {
                repository.applyPreset(preset)
            } catch (_: IllegalStateException) {
                // The repository sends the rejection to lightingSyncError.
            }
        }
    }
}
