package com.pulseshift.app.ui.screens.presets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseshift.app.data.repository.PulseShiftRepository
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.ShiftLightConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class PresetsViewModel(
    private val repository: PulseShiftRepository
) : ViewModel() {
    val presets: StateFlow<List<Preset>> = repository.presets
    val connectionState = repository.connectionState
    val authState = repository.authState

    private val _userMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    private val _persistenceError = MutableStateFlow<String?>(null)
    val persistenceError: StateFlow<String?> = _persistenceError.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    fun clearPersistenceError() {
        _persistenceError.value = null
    }

    fun applyPreset(preset: Preset) {
        viewModelScope.launch {
            if (_isBusy.value) return@launch
            _isBusy.value = true
            try {
                val result = repository.applyPreset(preset)
                _userMessage.tryEmit(result.message(preset.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _userMessage.tryEmit("Failed to apply preset: ${e.message}")
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun createPreset(
        name: String,
        effect: EffectType,
        colors: List<RgbColor>,
        brightness: Int = 255,
        speed: Int,
        shiftConfig: ShiftLightConfig? = null
    ) {
        val newPreset = Preset(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Custom Preset" },
            effect = effect,
            ledColors = colors,
            brightness = brightness,
            effectSpeed = speed,
            isCustom = true,
            shiftLightConfig = shiftConfig,
            createdAt = System.currentTimeMillis()
        )
        viewModelScope.launch {
            try {
                repository.savePreset(newPreset)
                _persistenceError.value = null
                _userMessage.tryEmit("Saved preset '${newPreset.name}'")
            } catch (e: Exception) {
                _persistenceError.value = e.message ?: "Failed to persist preset"
                _userMessage.tryEmit("Failed to save preset: ${e.message}")
            }
        }
    }

    fun duplicatePreset(preset: Preset) {
        viewModelScope.launch {
            try {
                val copy = repository.duplicatePreset(preset)
                if (copy != null) {
                    _persistenceError.value = null
                    _userMessage.tryEmit("Created '${copy.name}'")
                }
            } catch (e: Exception) {
                _persistenceError.value = e.message ?: "Failed to duplicate preset"
                _userMessage.tryEmit("Failed to duplicate preset: ${e.message}")
            }
        }
    }

    fun renamePreset(preset: Preset, newName: String) {
        viewModelScope.launch {
            try {
                repository.updatePreset(preset.copy(name = newName.ifBlank { preset.name }))
                _persistenceError.value = null
                _userMessage.tryEmit("Renamed to '$newName'")
            } catch (e: Exception) {
                _persistenceError.value = e.message ?: "Failed to rename preset"
                _userMessage.tryEmit("Failed to rename preset: ${e.message}")
            }
        }
    }

    fun updatePreset(preset: Preset) {
        viewModelScope.launch {
            try {
                repository.updatePreset(preset)
                _persistenceError.value = null
                _userMessage.tryEmit("Updated preset '${preset.name}'")
            } catch (e: Exception) {
                _persistenceError.value = e.message ?: "Failed to update preset"
                _userMessage.tryEmit("Failed to update preset: ${e.message}")
            }
        }
    }

    fun deletePreset(id: String) {
        viewModelScope.launch {
            try {
                repository.deletePreset(id)
                _persistenceError.value = null
                _userMessage.tryEmit("Preset deleted")
            } catch (e: Exception) {
                _persistenceError.value = e.message ?: "Failed to delete preset"
                _userMessage.tryEmit("Failed to delete preset: ${e.message}")
            }
        }
    }

    fun restoreDefaults() {
        viewModelScope.launch {
            try {
                repository.restoreDefaultPresets()
                _persistenceError.value = null
                _userMessage.tryEmit("Restored factory presets")
            } catch (e: Exception) {
                _persistenceError.value = e.message ?: "Failed to restore factory presets"
                _userMessage.tryEmit("Failed to restore factory presets: ${e.message}")
            }
        }
    }
}
