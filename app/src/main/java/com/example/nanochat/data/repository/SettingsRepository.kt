package com.example.nanochat.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        private val CONTEXT_WINDOW_SIZE_KEY = intPreferencesKey("context_window_size")
        private val SYSTEM_PROMPT_KEY = stringPreferencesKey("system_prompt")
        private val TEMPERATURE_KEY = floatPreferencesKey("temperature")
        private val TOP_K_KEY = intPreferencesKey("top_k")

        const val DEFAULT_CONTEXT_WINDOW_SIZE = 4096
        const val DEFAULT_SYSTEM_PROMPT = ""
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_TOP_K = 40
    }

    val contextWindowSize: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[CONTEXT_WINDOW_SIZE_KEY] ?: DEFAULT_CONTEXT_WINDOW_SIZE
    }

    val systemPrompt: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[SYSTEM_PROMPT_KEY] ?: DEFAULT_SYSTEM_PROMPT
    }

    val temperature: Flow<Float> = context.dataStore.data.map { preferences ->
        preferences[TEMPERATURE_KEY] ?: DEFAULT_TEMPERATURE
    }

    val topK: Flow<Int> = context.dataStore.data.map { preferences ->
        preferences[TOP_K_KEY] ?: DEFAULT_TOP_K
    }

    suspend fun saveContextWindowSize(size: Int) {
        context.dataStore.edit { preferences ->
            preferences[CONTEXT_WINDOW_SIZE_KEY] = size
        }
    }

    suspend fun saveTemperature(value: Float) {
        context.dataStore.edit { preferences ->
            preferences[TEMPERATURE_KEY] = value
        }
    }

    suspend fun saveTopK(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[TOP_K_KEY] = value
        }
    }

    suspend fun saveSettings(contextWindowSize: Int, systemPrompt: String, temperature: Float, topK: Int) {
        context.dataStore.edit { preferences ->
            preferences[CONTEXT_WINDOW_SIZE_KEY] = contextWindowSize
            preferences[SYSTEM_PROMPT_KEY] = systemPrompt
            preferences[TEMPERATURE_KEY] = temperature
            preferences[TOP_K_KEY] = topK
        }
    }
}
