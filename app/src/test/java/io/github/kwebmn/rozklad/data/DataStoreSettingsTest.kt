package io.github.kwebmn.rozklad.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreSettingsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `defaults then round trip`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val store = DataStoreSettings(
            PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") },
        )
        assertEquals(AppSettings(), store.settings.first())
        store.update { it.copy(reminderMinutes = 15, theme = ThemeMode.DARK, changeNotifications = false, lastUpdateCheck = 99) }
        assertEquals(
            AppSettings(remindersEnabled = true, reminderMinutes = 15, changeNotifications = false, theme = ThemeMode.DARK, lastUpdateCheck = 99),
            store.settings.first(),
        )
        scope.cancel()
    }
}
