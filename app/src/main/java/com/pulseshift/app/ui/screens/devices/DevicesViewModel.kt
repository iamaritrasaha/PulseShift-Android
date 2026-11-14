package com.pulseshift.app.ui.screens.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.data.ble.PulseShiftBleManager
import com.pulseshift.app.data.repository.PulseShiftRepository
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.CanHealthState
import com.pulseshift.app.domain.model.RpmTelemetryState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DevicesViewModel(
    private val bleManager: PulseShiftBleManager,
    private val repository: PulseShiftRepository
) : ViewModel() {
    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState
    val discoveredDevices: StateFlow<List<BleDevice>> = bleManager.discoveredDevices
    val connectedDevice: StateFlow<BleDevice?> = repository.connectedDevice
    val rpmTelemetryState: StateFlow<RpmTelemetryState?> = repository.rpmTelemetryState
    val canHealthState: StateFlow<CanHealthState?> = repository.canHealthState
    val authState: StateFlow<com.pulseshift.app.domain.model.AuthState> = repository.authState
    val isAuthenticated: StateFlow<Boolean> = repository.isAuthenticated

    private val _enrollmentMessage = MutableStateFlow<String?>(null)
    val enrollmentMessage: StateFlow<String?> = _enrollmentMessage.asStateFlow()

    fun startScan() {
        bleManager.startScan()
    }

    fun stopScan() {
        bleManager.stopScan()
    }

    fun connectDevice(address: String) {
        viewModelScope.launch {
            bleManager.connect(address)
        }
    }

    fun disconnect() {
        bleManager.disconnect()
    }

    /** Enroll with a fresh secret. Requires the physical BOOT window on the ESP32. */
    fun enrollOwner(secret: ByteArray) {
        viewModelScope.launch {
            val ok = repository.enrollOwner(secret)
            _enrollmentMessage.value = if (ok) {
                "Owner enrolled. This phone is now the authenticated owner."
            } else {
                "Enrollment failed. Press the BOOT button on the ESP32 to open the 60-second enrollment window, then try again."
            }
        }
    }

    fun authenticate() {
        viewModelScope.launch {
            val ok = repository.authenticateChallengeResponse()
            _enrollmentMessage.value = if (ok) "Session authorized." else "Authentication failed."
        }
    }

    fun clearEnrollmentMessage() {
        _enrollmentMessage.value = null
    }
}
