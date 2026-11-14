package com.pulseshift.app.ui.screens.presets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulseshift.app.data.repository.PulseShiftRepository
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.RgbColor
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

class PresetsViewModel(
    private val repository: PulseShiftRepository
) : ViewModel() {
    val presets: StateFlow<List<Preset>> = repository.presets

    fun applyPreset(preset: Preset) {
        viewModelScope.launch {
            repository.applyPreset(preset)
        }
    }

    fun createPreset(name: String, effect: EffectType, colors: List<RgbColor>, brightness: Int, speed: Int) {
        val newPreset = Preset(
            id = UUID.randomUUID().toString(),
            name = name,
            effect = effect,
            ledColors = colors,
            brightness = brightness,
            effectSpeed = speed,
            isCustom = true
        )
        viewModelScope.launch {
            repository.savePreset(newPreset)
        }
    }

    fun duplicatePreset(preset: Preset) {
        val duplicate = preset.copy(
            id = UUID.randomUUID().toString(),
            name = "${preset.name} (Copy)",
            isCustom = true
        )
        viewModelScope.launch {
            repository.savePreset(duplicate)
        }
    }

    fun deletePreset(id: String) {
        viewModelScope.launch {
            repository.deletePreset(id)
        }
    }

    fun restoreDefaults() {
        viewModelScope.launch {
            repository.restoreDefaultPresets()
        }
    }
}
