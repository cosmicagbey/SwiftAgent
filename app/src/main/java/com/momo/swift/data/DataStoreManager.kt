package com.momo.swift.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

/**
 * Extension property to provide a singleton [DataStore] instance for the application.
 */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Manages the persistence of application settings using Android DataStore.
 * Settings are stored as a JSON string using Kotlin Serialization.
 *
 * @param context The application context.
 */
class DataStoreManager(private val context: Context) {
    companion object {
        /**
         * Key for storing the serialized [AppSettings] in DataStore.
         */
        val SETTINGS_KEY = stringPreferencesKey("app_settings")
    }

    /**
     * A [Flow] of [AppSettings] that emits updates whenever the stored settings change.
     */
    val appSettingsFlow: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        val jsonString = preferences[SETTINGS_KEY]
        val settings = if (jsonString != null) {
            try {
                Json.decodeFromString<AppSettings>(jsonString)
            } catch (e: Exception) {
                AppSettings()
            }
        } else {
            AppSettings()
        }
        if (settings.firstLaunchTimestamp == 0L) {
            settings.copy(firstLaunchTimestamp = System.currentTimeMillis())
        } else {
            settings
        }
    }

    /**
     * Updates the application settings.
     *
     * @param updateParams A lambda that takes the current [AppSettings] and returns the modified [AppSettings].
     */
    suspend fun updateSettings(updateParams: (AppSettings) -> AppSettings) {
        context.dataStore.edit { preferences ->
            val currentJson = preferences[SETTINGS_KEY]
            val currentSettings = if (currentJson != null) {
                try {
                    Json.decodeFromString<AppSettings>(currentJson)
                } catch (e: Exception) {
                    AppSettings()
                }
            } else {
                AppSettings()
            }
            val currentSettingsWithTime = if (currentSettings.firstLaunchTimestamp == 0L) {
                currentSettings.copy(firstLaunchTimestamp = System.currentTimeMillis())
            } else {
                currentSettings
            }
            val newSettings = updateParams(currentSettingsWithTime)
            preferences[SETTINGS_KEY] = Json.encodeToString(newSettings)
        }
    }
}
