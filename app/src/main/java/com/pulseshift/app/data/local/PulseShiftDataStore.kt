package com.pulseshift.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pulseshift_preferences")

class PulseShiftDataStore(private val context: Context) {
    companion object {
        val KEY_IS_ONBOARDING_COMPLETED = booleanPreferencesKey("is_onboarding_completed")
        val KEY_THEME_MODE = intPreferencesKey("theme_mode") // 0: System, 1: Light, 2: Dark
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val KEY_CUSTOM_PRESETS_JSON = stringPreferencesKey("custom_presets_json")
    }

    val isOnboardingCompleted: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_IS_ONBOARDING_COMPLETED] ?: false
    }

    val themeMode: Flow<Int> = context.dataStore.data.map {
        it[KEY_THEME_MODE] ?: 0
    }

    // Opt-in: Material You recoloring overrides the instrument-panel identity.
    val dynamicColor: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_DYNAMIC_COLOR] ?: false
    }

    val autoReconnect: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_AUTO_RECONNECT] ?: true
    }

    val customPresetsJson: Flow<String> = context.dataStore.data.map {
        it[KEY_CUSTOM_PRESETS_JSON] ?: ""
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[KEY_IS_ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setThemeMode(mode: Int) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[KEY_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_RECONNECT] = enabled }
    }

    suspend fun setCustomPresetsJson(json: String) {
        context.dataStore.edit { it[KEY_CUSTOM_PRESETS_JSON] = json }
    }
}
