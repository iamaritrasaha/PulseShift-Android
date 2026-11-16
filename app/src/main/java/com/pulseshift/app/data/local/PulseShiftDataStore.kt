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
import org.json.JSONArray
import org.json.JSONObject

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pulseshift_preferences")

class PulseShiftDataStore(private val context: Context) {
    companion object {
        val KEY_IS_ONBOARDING_COMPLETED = booleanPreferencesKey("is_onboarding_completed")
        val KEY_THEME_MODE = intPreferencesKey("theme_mode") // 0: System, 1: Light, 2: Dark
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val KEY_CUSTOM_PRESETS_JSON = stringPreferencesKey("custom_presets_json")
        val KEY_PRESETS_VERSION = intPreferencesKey("presets_version")
        const val CURRENT_PRESETS_VERSION = 2

        /**
         * Migrates legacy custom presets JSON across schema versions.
         * Strictly preserves user-configured brightness and properties,
         * assigning 255 only if brightness was completely missing.
         */
        fun migrateCustomPresetsJson(rawJson: String): String {
            val trimmed = rawJson.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("[") || !trimmed.endsWith("]")) {
                return rawJson
            }

            val objects = mutableListOf<String>()
            var depth = 0
            var inString = false
            var escape = false
            var startIdx = -1

            for (i in trimmed.indices) {
                val c = trimmed[i]
                if (escape) {
                    escape = false
                    continue
                }
                if (c == '\\') {
                    escape = true
                    continue
                }
                if (c == '"') {
                    inString = !inString
                    continue
                }
                if (!inString) {
                    if (c == '{') {
                        if (depth == 0) startIdx = i
                        depth++
                    } else if (c == '}') {
                        depth--
                        if (depth == 0 && startIdx != -1) {
                            objects.add(trimmed.substring(startIdx, i + 1))
                            startIdx = -1
                        }
                    }
                }
            }

            if (objects.isEmpty()) return rawJson

            val migratedObjects = objects.map { objStr ->
                var s = objStr.trim()
                if (s.startsWith("{") && s.endsWith("}")) {
                    val inner = s.substring(1, s.length - 1).trim()
                    val hasBrightness = Regex("\"brightness\"\\s*:").containsMatchIn(inner)
                    val hasDirection = Regex("\"ledDirection\"\\s*:").containsMatchIn(inner)
                    val hasStartupAnim = Regex("\"startupAnimationId\"\\s*:").containsMatchIn(inner)
                    val hasStartupDur = Regex("\"startupDurationMs\"\\s*:").containsMatchIn(inner)

                    val additions = mutableListOf<String>()
                    if (!hasBrightness) additions.add("\"brightness\":255")
                    if (!hasDirection) additions.add("\"ledDirection\":0")
                    if (!hasStartupAnim) additions.add("\"startupAnimationId\":1")
                    if (!hasStartupDur) additions.add("\"startupDurationMs\":2000")

                    if (additions.isNotEmpty()) {
                        val prefix = if (inner.isNotEmpty()) {
                            if (inner.endsWith(",")) inner else "$inner,"
                        } else ""
                        s = "{$prefix${additions.joinToString(",")}}"
                    }
                }
                s
            }

            return "[${migratedObjects.joinToString(",")}]"
        }
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

    suspend fun migrateCustomPresetsIfNeeded() {
        context.dataStore.edit { prefs ->
            val currentVersion = prefs[KEY_PRESETS_VERSION] ?: 1
            if (currentVersion < CURRENT_PRESETS_VERSION) {
                val existingJson = prefs[KEY_CUSTOM_PRESETS_JSON] ?: ""
                if (existingJson.isNotBlank()) {
                    val migratedJson = migrateCustomPresetsJson(existingJson)
                    prefs[KEY_CUSTOM_PRESETS_JSON] = migratedJson
                }
                prefs[KEY_PRESETS_VERSION] = CURRENT_PRESETS_VERSION
            }
        }
    }
}
