package io.github.kwebmn.rozklad.ui.schedule

import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.data.remote.SiteFormatException
import io.github.kwebmn.rozklad.ui.PARSE_ERROR
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.LessonChange
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.fakes.FIXED_CLOCK
import io.github.kwebmn.rozklad.fakes.FRI
import io.github.kwebmn.rozklad.fakes.FakeLessonStore
import io.github.kwebmn.rozklad.fakes.FakeScheduleSource
import io.github.kwebmn.rozklad.fakes.MainDispatcherRule
import io.github.kwebmn.rozklad.fakes.MutableClock
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

    private fun vm(clock: Clock = FIXED_CLOCK, onSynced: (Subscription, List<LessonChange>) -> Unit = { _, _ -> }) =
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
        val vm = vm(onSynced = { _, _ -> synced++ })
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

    @Test
    fun `parse error refresh shows message and keeps lessons`() {
        source.error = SiteFormatException("bad")
        val vm = vm()
        vm.refresh()
        assertEquals(PARSE_ERROR, vm.state.value.error)
        assertEquals(2, vm.state.value.dayLessons.size)
    }

    @Test
    fun `on-demand loads in one week are deduplicated`() {
        val vm = vm()
        vm.showDate(LocalDate.of(2026, 10, 27))
        vm.shiftDay(1)
        vm.shiftDay(1)
        assertEquals(1, source.fetchCalls.size)
    }

    @Test
    fun `switching subscription clears previous offline status`() {
        source.error = NetworkException(IOException("down"))
        val vm = vm()
        vm.refresh()
        assertTrue(vm.state.value.offline)
        source.error = null
        vm.selectSubscription(teacherId)
        assertEquals(false, vm.state.value.offline)
    }

    @Test
    fun `refresh passes found changes to the callback`() {
        var received: Pair<Subscription, List<LessonChange>>? = null
        val vm = vm(onSynced = { sub, changes -> received = sub to changes })
        val moved = lesson(FRI, 1, room = "к.1ауд.503")
        source.schedule = source.schedule.copy(lessons = listOf(lesson(THU, 1), lesson(THU, 2), moved))
        vm.refresh()
        assertEquals(groupId, received?.first?.id)
        assertEquals(listOf(LessonChange.Changed(lesson(FRI, 1), moved)), received?.second)
    }

    @Test
    fun `reopening on the next day selects the new start day`() {
        val clock = MutableClock("2026-10-15T09:00")
        val vm = vm(clock)
        assertEquals(THU, vm.state.value.selectedDate)
        clock.set("2026-10-16T07:00")
        vm.onStart()
        assertEquals(FRI, vm.state.value.selectedDate)
    }

    @Test
    fun `reopening with a stale subscription refreshes`() {
        val clock = MutableClock("2026-10-15T09:00")
        val vm = vm(clock)
        assertTrue(source.fetchCalls.isEmpty())
        clock.set("2026-10-15T10:00")
        vm.onStart()
        assertEquals(1, source.fetchCalls.size)
    }

    @Test
    fun `reopening on the same day keeps a manually picked date`() {
        val clock = MutableClock("2026-10-15T09:00")
        val vm = vm(clock)
        vm.shiftDay(-1)
        clock.set("2026-10-15T09:10")
        vm.onStart()
        assertEquals(LocalDate.of(2026, 10, 14), vm.state.value.selectedDate)
        assertTrue(source.fetchCalls.isEmpty())
    }

    @Test
    fun `reopening on another day overrides a manually picked date`() {
        val clock = MutableClock("2026-10-15T09:00")
        val vm = vm(clock)
        vm.shiftDay(-1)
        clock.set("2026-10-16T07:00")
        vm.onStart()
        assertEquals(FRI, vm.state.value.selectedDate)
    }

    @Test
    fun `reopening on the same day without a manual pick re-chooses the start day`() {
        val clock = MutableClock("2026-10-15T09:00")
        val vm = vm(clock)
        clock.set("2026-10-15T09:20")
        vm.onStart()
        assertEquals(THU, vm.state.value.selectedDate)
        clock.set("2026-10-15T12:00")
        vm.onStart()
        assertEquals(FRI, vm.state.value.selectedDate)
    }
}
