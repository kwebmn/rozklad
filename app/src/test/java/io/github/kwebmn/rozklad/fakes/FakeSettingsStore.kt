package io.github.kwebmn.rozklad.fakes

import io.github.kwebmn.rozklad.data.AppSettings
import io.github.kwebmn.rozklad.data.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
    val flow = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = flow
    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        flow.value = transform(flow.value)
    }
}
