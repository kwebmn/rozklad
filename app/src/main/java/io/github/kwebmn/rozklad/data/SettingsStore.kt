package io.github.kwebmn.rozklad.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val remindersEnabled: Boolean = true,
    val reminderMinutes: Int = 10,
    val changeNotifications: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val lastUpdateCheck: Long = 0L,
)

interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

class DataStoreSettings(private val dataStore: DataStore<Preferences>) : SettingsStore {
    private object Keys {
        val REMINDERS = booleanPreferencesKey("reminders_enabled")
        val MINUTES = intPreferencesKey("reminder_minutes")
        val CHANGES = booleanPreferencesKey("change_notifications")
        val THEME = stringPreferencesKey("theme")
        val LAST_UPDATE = longPreferencesKey("last_update_check")
    }

    override val settings: Flow<AppSettings> = dataStore.data.map { it.toSettings() }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.REMINDERS] = next.remindersEnabled
            prefs[Keys.MINUTES] = next.reminderMinutes
            prefs[Keys.CHANGES] = next.changeNotifications
            prefs[Keys.THEME] = next.theme.name
            prefs[Keys.LAST_UPDATE] = next.lastUpdateCheck
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            remindersEnabled = this[Keys.REMINDERS] ?: d.remindersEnabled,
            reminderMinutes = this[Keys.MINUTES] ?: d.reminderMinutes,
            changeNotifications = this[Keys.CHANGES] ?: d.changeNotifications,
            theme = this[Keys.THEME]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: d.theme,
            lastUpdateCheck = this[Keys.LAST_UPDATE] ?: d.lastUpdateCheck,
        )
    }
}
