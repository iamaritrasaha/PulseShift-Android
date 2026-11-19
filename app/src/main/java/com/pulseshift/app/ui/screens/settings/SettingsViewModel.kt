package com.pulseshift.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseshift.app.data.local.PulseShiftDataStore
import com.pulseshift.app.data.repository.PulseShiftRepository
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.OtaUpdateState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val dataStore: PulseShiftDataStore,
    private val repository: PulseShiftRepository
) : ViewModel() {
    val connectedDevice: StateFlow<BleDevice?> = repository.connectedDevice
    val isAuthenticated = repository.isAuthenticated
    val configSnapshot = repository.configSnapshot
    val saveErrors = repository.lightingSyncError
    private val _stripSave = MutableStateFlow<StripSaveState>(StripSaveState.Idle)
    val stripSave = _stripSave.asStateFlow()

    enum class StripSaveState { Idle, Saving, Saved, Unconfirmed }

    fun saveLedCount(count: Int) {
        if (_stripSave.value == StripSaveState.Saving) return
        _stripSave.value = StripSaveState.Saving
        viewModelScope.launch {
            _stripSave.value = if (repository.setAndSaveLedCount(count)) StripSaveState.Saved
                else StripSaveState.Unconfirmed
        }
    }

    fun acknowledgeStripSave() {
        _stripSave.value = StripSaveState.Idle
    }
    val otaUpdateState: StateFlow<OtaUpdateState> = repository.otaUpdateState

    val themeMode: StateFlow<Int> = dataStore.themeMode.stateIn(
        viewModelScope, SharingStarted.Eagerly, 0
    )
    val dynamicColor: StateFlow<Boolean> = dataStore.dynamicColor.stateIn(
        viewModelScope, SharingStarted.Eagerly, true
    )
    val autoReconnect: StateFlow<Boolean> = dataStore.autoReconnect.stateIn(
        viewModelScope, SharingStarted.Eagerly, true
    )
    private var otaJob: Job? = null

    fun startOtaUpdate(imageBytes: ByteArray) {
        if (otaJob?.isActive == true) return
        otaJob = viewModelScope.launch {
            repository.startOtaUpdate(imageBytes)
        }
    }

    fun cancelOtaUpdate() {
        viewModelScope.launch {
            repository.cancelOtaUpdate()
        }
    }

    fun setThemeMode(mode: Int) {
        viewModelScope.launch {
            dataStore.setThemeMode(mode)
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setDynamicColor(enabled)
        }
    }

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.setAutoReconnect(enabled)
        }
    }

    fun restoreDefaults() {
        viewModelScope.launch {
            repository.restoreDefaultPresets()
        }
    }
}
