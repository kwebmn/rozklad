package io.github.kwebmn.rozklad.work

import io.github.kwebmn.rozklad.data.AppSettings
import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.data.remote.SiteFormatException
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.LessonChange
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.fakes.FIXED_CLOCK
import io.github.kwebmn.rozklad.fakes.FRI
import io.github.kwebmn.rozklad.fakes.FakeLessonStore
import io.github.kwebmn.rozklad.fakes.FakeScheduleSource
import io.github.kwebmn.rozklad.fakes.FakeSettingsStore
import io.github.kwebmn.rozklad.fakes.lesson
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class SyncRunnerTest {
    private val source = FakeScheduleSource()
    private val store = FakeLessonStore()
    private val settings = FakeSettingsStore()
    private val notified = mutableListOf<Pair<String, List<LessonChange>>>()
    private val runner = SyncRunner(ScheduleRepository(source, store, FIXED_CLOCK), settings) { sub, changes ->
        notified += sub.name to changes
    }
    private val window = DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 25))

    @Before
    fun seed() = runTest {
        val synced = FIXED_CLOCK.millis() - 3_600_000
        val primary = store.insertSubscription(
            Subscription(type = SubscriptionType.GROUP, name = "Основна", siteId = "1", isPrimary = true, lastSyncedAt = synced),
        )
        val other = store.insertSubscription(
            Subscription(type = SubscriptionType.GROUP, name = "Інша", siteId = "2", lastSyncedAt = synced),
        )
        store.replaceLessons(primary, window, listOf(lesson(FRI, 2)))
        store.replaceLessons(other, window, listOf(lesson(FRI, 2)))
        source.schedule = source.schedule.copy(lessons = listOf(lesson(FRI, 2, room = "к.1ауд.503")))
    }

    @Test
    fun `notifies only for the primary subscription`() = runTest {
        assertFalse(runner.run())
        assertEquals(listOf("Основна"), notified.map { it.first })
        assertEquals(1, notified.single().second.size)
        assertEquals(2, source.fetchCalls.size)
    }

    @Test
    fun `respects the notification setting`() = runTest {
        settings.flow.value = AppSettings(changeNotifications = false)
        runner.run()
        assertTrue(notified.isEmpty())
    }

    @Test
    fun `network failure asks for retry`() = runTest {
        source.error = NetworkException(IOException("down"))
        assertTrue(runner.run())
        assertTrue(notified.isEmpty())
    }

    @Test
    fun `format error does not ask for retry`() = runTest {
        source.error = SiteFormatException("broken")
        assertFalse(runner.run())
    }
}
