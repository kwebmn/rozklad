package io.github.kwebmn.rozklad.ui.settings

import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.data.ThemeMode
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.fakes.FIXED_CLOCK
import io.github.kwebmn.rozklad.fakes.FakeLessonStore
import io.github.kwebmn.rozklad.fakes.FakeScheduleSource
import io.github.kwebmn.rozklad.fakes.FakeSettingsStore
import io.github.kwebmn.rozklad.fakes.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val store = FakeLessonStore()
    private val settings = FakeSettingsStore()
    private var changed = 0
    private val vm by lazy {
        SettingsViewModel(
            ScheduleRepository(FakeScheduleSource(), store, FIXED_CLOCK),
            settings,
            onScheduleChanged = { changed++ },
        )
    }

    @Test
    fun `reminder minutes are saved and reschedule`() {
        vm.setReminderMinutes(15)
        assertEquals(15, settings.flow.value.reminderMinutes)
        assertEquals(15, vm.state.value.settings.reminderMinutes)
        assertEquals(1, changed)
    }

    @Test
    fun `disabling reminders reschedules`() {
        vm.setRemindersEnabled(false)
        assertEquals(false, settings.flow.value.remindersEnabled)
        assertEquals(1, changed)
    }

    @Test
    fun `theme and change notifications do not reschedule`() {
        vm.setTheme(ThemeMode.DARK)
        vm.setChangeNotifications(false)
        assertEquals(ThemeMode.DARK, settings.flow.value.theme)
        assertEquals(false, settings.flow.value.changeNotifications)
        assertEquals(0, changed)
    }

    @Test
    fun `deleting a subscription updates the list`() = runTest {
        val a = store.insertSubscription(Subscription(type = SubscriptionType.GROUP, name = "A", siteId = "1", isPrimary = true))
        store.insertSubscription(Subscription(type = SubscriptionType.GROUP, name = "B", siteId = "2"))
        vm.delete(a)
        assertEquals(listOf("B"), vm.state.value.subscriptions.map { it.name })
        assertEquals(true, vm.state.value.subscriptions.single().isPrimary)
        assertEquals(1, changed)
    }

    @Test
    fun `setPrimary flips primary flag and reschedules`() = runTest {
        store.insertSubscription(Subscription(type = SubscriptionType.GROUP, name = "A", siteId = "1", isPrimary = true))
        val b = store.insertSubscription(Subscription(type = SubscriptionType.GROUP, name = "B", siteId = "2"))
        vm.setPrimary(b)
        assertEquals(listOf("B"), vm.state.value.subscriptions.filter { it.isPrimary }.map { it.name })
        assertEquals(1, changed)
    }
}
