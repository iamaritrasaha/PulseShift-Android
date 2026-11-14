package com.pulseshift.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseshift.app.data.local.PulseShiftDataStore
import com.pulseshift.app.data.repository.PulseShiftRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val dataStore: PulseShiftDataStore,
    private val repository: PulseShiftRepository
) : ViewModel() {
    val themeMode: StateFlow<Int> = dataStore.themeMode.stateIn(
        viewModelScope, SharingStarted.Eagerly, 0
    )
    val dynamicColor: StateFlow<Boolean> = dataStore.dynamicColor.stateIn(
        viewModelScope, SharingStarted.Eagerly, true
    )
    val autoReconnect: StateFlow<Boolean> = dataStore.autoReconnect.stateIn(
        viewModelScope, SharingStarted.Eagerly, true
    )

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
