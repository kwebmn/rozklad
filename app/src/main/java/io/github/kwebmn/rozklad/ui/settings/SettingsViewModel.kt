package io.github.kwebmn.rozklad.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kwebmn.rozklad.data.AppSettings
import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.data.SettingsStore
import io.github.kwebmn.rozklad.data.ThemeMode
import io.github.kwebmn.rozklad.domain.Subscription
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

class SettingsViewModel(
    private val repo: ScheduleRepository,
    private val settings: SettingsStore,
    private val onScheduleChanged: () -> Unit = {},
) : ViewModel() {
    val state: StateFlow<SettingsUiState> =
        combine(repo.subscriptions, settings.settings, ::SettingsUiState)
            .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun setPrimary(id: Long) = viewModelScope.launch {
        repo.setPrimary(id)
        onScheduleChanged()
    }

    fun delete(id: Long) = viewModelScope.launch {
        repo.deleteSubscription(id)
        onScheduleChanged()
    }

    fun setRemindersEnabled(enabled: Boolean) = viewModelScope.launch {
        settings.update { it.copy(remindersEnabled = enabled) }
        onScheduleChanged()
    }

    fun setReminderMinutes(minutes: Int) = viewModelScope.launch {
        settings.update { it.copy(reminderMinutes = minutes) }
        onScheduleChanged()
    }

    fun setChangeNotifications(enabled: Boolean) = viewModelScope.launch {
        settings.update { it.copy(changeNotifications = enabled) }
    }

    fun setTheme(theme: ThemeMode) = viewModelScope.launch {
        settings.update { it.copy(theme = theme) }
    }
}
