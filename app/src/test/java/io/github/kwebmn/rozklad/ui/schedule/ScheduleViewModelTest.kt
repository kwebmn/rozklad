package io.github.kwebmn.rozklad.ui.schedule

import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.fakes.FIXED_CLOCK
import io.github.kwebmn.rozklad.fakes.FRI
import io.github.kwebmn.rozklad.fakes.FakeLessonStore
import io.github.kwebmn.rozklad.fakes.FakeScheduleSource
import io.github.kwebmn.rozklad.fakes.MainDispatcherRule
import io.github.kwebmn.rozklad.fakes.THU
import io.github.kwebmn.rozklad.fakes.clockAt
import io.github.kwebmn.rozklad.fakes.lesson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModelTest {
    @get:Rule
    val main = MainDispatcherRule(UnconfinedTestDispatcher())

    private val source = FakeScheduleSource()
    private val store = FakeLessonStore()
    private var groupId = 0L
    private var teacherId = 0L

    @Before
    fun seed() = runTest {
        groupId = store.insertSubscription(
            Subscription(type = SubscriptionType.GROUP, name = "ІПЗс-24-1", siteId = "-607", isPrimary = true, lastSyncedAt = FIXED_CLOCK.millis()),
        )
        teacherId = store.insertSubscription(
            Subscription(type = SubscriptionType.TEACHER, name = "Демчина", siteId = "66", lastSyncedAt = FIXED_CLOCK.millis()),
        )
        val window = DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 25))
        store.replaceLessons(groupId, window, listOf(lesson(THU, 1), lesson(THU, 2), lesson(FRI, 1)))
        store.replaceLessons(teacherId, window, listOf(lesson(THU, 3, "Лекція")))
    }

    private fun vm(clock: Clock = FIXED_CLOCK, onSynced: () -> Unit = {}) =
        ScheduleViewModel(ScheduleRepository(source, store, clock), clock, onSynced)

    @Test
    fun `opens today while pairs remain`() {
        val state = vm().state.value
        assertEquals("ІПЗс-24-1", state.current?.name)
        assertEquals(THU, state.selectedDate)
        assertEquals(LocalDate.of(2026, 10, 12), state.weekStart)
        assertEquals(2, state.dayLessons.size)
    }

    @Test
    fun `opens next day after the last pair`() {
        assertEquals(FRI, vm(clockAt("2026-10-15T12:00")).state.value.selectedDate)
    }

    @Test
    fun `week navigation inside the window does not fetch`() {
        val vm = vm()
        vm.shiftWeek(1)
        assertEquals(LocalDate.of(2026, 10, 22), vm.state.value.selectedDate)
        assertEquals(LocalDate.of(2026, 10, 19), vm.state.value.weekStart)
        assertTrue(source.fetchCalls.isEmpty())
    }

    @Test
    fun `week outside the window is loaded`() {
        val vm = vm()
        vm.shiftWeek(2)
        assertEquals(
            DateRange(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)),
            source.fetchCalls.single().range,
        )
    }

    @Test
    fun `offline refresh keeps lessons`() {
        source.error = NetworkException(IOException("down"))
        val vm = vm()
        vm.refresh()
        assertTrue(vm.state.value.offline)
        assertEquals(2, vm.state.value.dayLessons.size)
    }

    @Test
    fun `successful refresh notifies`() {
        var synced = 0
        val vm = vm(onSynced = { synced++ })
        vm.refresh()
        assertEquals(1, synced)
        assertEquals(false, vm.state.value.refreshing)
    }

    @Test
    fun `stale subscription refreshes on open`() = runTest {
        store.updateSubscription(store.subscriptions().first().copy(lastSyncedAt = null))
        vm()
        assertEquals(1, source.fetchCalls.size)
    }

    @Test
    fun `switching subscription shows its lessons`() {
        val vm = vm()
        vm.selectSubscription(teacherId)
        assertEquals("Демчина", vm.state.value.current?.name)
        assertEquals(listOf("Лекція"), vm.state.value.dayLessons.map { it.subject })
    }
}
