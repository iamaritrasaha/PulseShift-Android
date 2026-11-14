package com.pulseshift.app.ui.screens.presets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseshift.app.data.repository.PulseShiftRepository
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.ShiftLightConfig
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

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    fun applyPreset(preset: Preset) {
        viewModelScope.launch {
            _isBusy.value = true
            try {
                repository.applyPreset(preset)
                _userMessage.tryEmit("Applied preset '${preset.name}' to ESP32")
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
        brightness: Int,
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
            repository.savePreset(newPreset)
            _userMessage.tryEmit("Saved preset '${newPreset.name}'")
        }
    }

    fun duplicatePreset(preset: Preset) {
        viewModelScope.launch {
            val copy = repository.duplicatePreset(preset)
            if (copy != null) {
                _userMessage.tryEmit("Created '${copy.name}'")
            }
        }
    }

    fun renamePreset(preset: Preset, newName: String) {
        viewModelScope.launch {
            repository.updatePreset(preset.copy(name = newName.ifBlank { preset.name }))
            _userMessage.tryEmit("Renamed to '$newName'")
        }
    }

    fun updatePreset(preset: Preset) {
        viewModelScope.launch {
            repository.updatePreset(preset)
            _userMessage.tryEmit("Updated preset '${preset.name}'")
        }
    }

    fun deletePreset(id: String) {
        viewModelScope.launch {
            repository.deletePreset(id)
            _userMessage.tryEmit("Preset deleted")
        }
    }

    fun restoreDefaults() {
        viewModelScope.launch {
            repository.restoreDefaultPresets()
            _userMessage.tryEmit("Restored factory presets")
        }
    }
}
