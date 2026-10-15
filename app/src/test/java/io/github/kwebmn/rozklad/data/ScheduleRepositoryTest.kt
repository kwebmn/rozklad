package io.github.kwebmn.rozklad.data

import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.data.remote.ParsedSchedule
import io.github.kwebmn.rozklad.data.remote.SiteFormatException
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.LessonChange
import io.github.kwebmn.rozklad.domain.SubscriptionType.GROUP
import io.github.kwebmn.rozklad.domain.SubscriptionType.TEACHER
import io.github.kwebmn.rozklad.fakes.FIXED_CLOCK
import io.github.kwebmn.rozklad.fakes.FRI
import io.github.kwebmn.rozklad.fakes.FakeLessonStore
import io.github.kwebmn.rozklad.fakes.FakeScheduleSource
import io.github.kwebmn.rozklad.fakes.THU
import io.github.kwebmn.rozklad.fakes.clockAt
import io.github.kwebmn.rozklad.fakes.lesson
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class ScheduleRepositoryTest {
    private val source = FakeScheduleSource()
    private val store = FakeLessonStore()
    private val repo = ScheduleRepository(source, store, FIXED_CLOCK)
    private val window = DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 25))

    @Test
    fun `first subscription is primary and keeps site id and lessons`() = runTest {
        source.schedule = ParsedSchedule(GROUP, "ІПЗс-24-1", "-607", listOf(lesson(THU, 1)))
        val sub = repo.addSubscription(GROUP, "ІПЗс-24-1")
        assertEquals("-607", sub.siteId)
        assertTrue(sub.isPrimary)
        assertEquals(FIXED_CLOCK.millis(), sub.lastSyncedAt)
        assertEquals(FakeScheduleSource.FetchCall(null, "ІПЗс-24-1", window), source.fetchCalls.single())
        assertEquals(listOf(lesson(THU, 1)), store.lessons(sub.id, window))
    }

    @Test
    fun `second subscription is not primary`() = runTest {
        repo.addSubscription(GROUP, "ІПЗс-24-1")
        source.schedule = ParsedSchedule(TEACHER, "Демчина Микола Миколайович", "66", emptyList())
        val second = repo.addSubscription(TEACHER, "Демчина Микола Миколайович")
        assertFalse(second.isPrimary)
    }

    @Test
    fun `adding the same subscription twice returns the existing one`() = runTest {
        val first = repo.addSubscription(GROUP, "ІПЗс-24-1")
        val again = repo.addSubscription(GROUP, "ІПЗс-24-1")
        assertEquals(first.id, again.id)
        assertEquals(1, source.fetchCalls.size)
        assertEquals(1, store.subscriptions().size)
    }

    @Test
    fun `empty period on add keeps null site id`() = runTest {
        source.schedule = ParsedSchedule(null, null, null, emptyList())
        val sub = repo.addSubscription(GROUP, "ІПЗс-24-1")
        assertNull(sub.siteId)
        assertEquals("ІПЗс-24-1", sub.name)
    }

    @Test
    fun `refresh returns changes and stores new lessons`() = runTest {
        source.schedule = ParsedSchedule(GROUP, "ІПЗс-24-1", "-607", listOf(lesson(FRI, 2)))
        val sub = repo.addSubscription(GROUP, "ІПЗс-24-1")
        val moved = lesson(FRI, 2, room = "к.1ауд.503")
        source.schedule = source.schedule.copy(lessons = listOf(moved))
        val changes = repo.refresh(sub)
        assertEquals(listOf(LessonChange.Changed(lesson(FRI, 2), moved)), changes)
        assertEquals(listOf(moved), store.lessons(sub.id, window))
        assertEquals(FakeScheduleSource.FetchCall("-607", "ІПЗс-24-1", window), source.fetchCalls.last())
    }

    @Test
    fun `first refresh without previous sync reports nothing`() = runTest {
        val id = store.insertSubscription(
            io.github.kwebmn.rozklad.domain.Subscription(type = GROUP, name = "ІПЗс-24-1", siteId = "-607"),
        )
        source.schedule = source.schedule.copy(lessons = listOf(lesson(FRI, 1)))
        assertTrue(repo.refresh(store.subscriptions().single { it.id == id }).isEmpty())
    }

    @Test
    fun `refresh learns site id when it was missing`() = runTest {
        source.schedule = ParsedSchedule(null, null, null, emptyList())
        val sub = repo.addSubscription(GROUP, "ІПЗс-24-1")
        source.schedule = ParsedSchedule(GROUP, "ІПЗс-24-1", "-607", emptyList())
        repo.refresh(sub)
        assertEquals("-607", store.subscriptions().single().siteId)
    }

    @Test
    fun `failed refresh keeps the cache`() {
        val sub = runBlocking {
            source.schedule = ParsedSchedule(GROUP, "ІПЗс-24-1", "-607", listOf(lesson(FRI, 2)))
            repo.addSubscription(GROUP, "ІПЗс-24-1")
        }
        source.error = SiteFormatException("broken")
        assertThrows(SiteFormatException::class.java) { runBlocking { repo.refresh(sub) } }
        source.error = NetworkException(IOException("down"))
        assertThrows(NetworkException::class.java) { runBlocking { repo.refresh(sub) } }
        assertEquals(listOf(lesson(FRI, 2)), runBlocking { store.lessons(sub.id, window) })
        assertEquals(FIXED_CLOCK.millis(), runBlocking { store.subscriptions().single().lastSyncedAt })
    }

    @Test
    fun `deleting the primary promotes another subscription`() = runTest {
        val first = repo.addSubscription(GROUP, "ІПЗс-24-1")
        val second = repo.addSubscription(GROUP, "ІПЗс-24-2")
        repo.deleteSubscription(first.id)
        assertEquals(second.id, repo.primary()?.id)
        assertTrue(store.subscriptions().single().isPrimary)
    }

    @Test
    fun `set primary switches the flag`() = runTest {
        val first = repo.addSubscription(GROUP, "ІПЗс-24-1")
        val second = repo.addSubscription(GROUP, "ІПЗс-24-2")
        repo.setPrimary(second.id)
        assertEquals(listOf(false, true), store.subscriptions().map { it.isPrimary })
        assertEquals(second.id, repo.primary()?.id)
        assertEquals(first.id, store.subscriptions().first().id)
    }

    @Test
    fun `newly entered trailing week is not reported as changes`() = runTest {
        val oct23 = LocalDate.of(2026, 10, 23)
        source.schedule = ParsedSchedule(GROUP, "ІПЗс-24-1", "-607", listOf(lesson(oct23, 1)))
        val sub = repo.addSubscription(GROUP, "ІПЗс-24-1")
        val nextWeek = ScheduleRepository(source, store, clockAt("2026-10-22T09:00"))
        source.schedule = source.schedule.copy(
            lessons = listOf(lesson(oct23, 1), lesson(LocalDate.of(2026, 10, 27), 2), lesson(LocalDate.of(2026, 10, 30), 3)),
        )
        assertTrue(nextWeek.refresh(store.subscriptions().single()).isEmpty())
        assertEquals(
            DateRange(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 11, 1)),
            source.fetchCalls.last().range,
        )
        assertEquals(2, store.lessons(sub.id, DateRange(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1))).size)
    }

    @Test
    fun `change inside previously covered range is still reported after the window moves`() = runTest {
        val oct23 = LocalDate.of(2026, 10, 23)
        source.schedule = ParsedSchedule(GROUP, "ІПЗс-24-1", "-607", listOf(lesson(oct23, 1)))
        repo.addSubscription(GROUP, "ІПЗс-24-1")
        val nextWeek = ScheduleRepository(source, store, clockAt("2026-10-22T09:00"))
        val moved = lesson(oct23, 1, room = "к.1ауд.503")
        source.schedule = source.schedule.copy(lessons = listOf(moved, lesson(LocalDate.of(2026, 10, 27), 2)))
        assertEquals(
            listOf(LessonChange.Changed(lesson(oct23, 1), moved)),
            nextWeek.refresh(store.subscriptions().single()),
        )
    }

    @Test
    fun `out-of-window refresh stores lessons but keeps last sync time`() = runTest {
        val sub = repo.addSubscription(GROUP, "ІПЗс-24-1")
        val later = ScheduleRepository(source, store, clockAt("2026-10-15T10:00"))
        val far = DateRange(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1))
        val nov2 = lesson(LocalDate.of(2026, 10, 28), 1)
        source.schedule = source.schedule.copy(lessons = listOf(nov2))
        assertTrue(later.refresh(store.subscriptions().single(), far).isEmpty())
        assertEquals(listOf(nov2), store.lessons(sub.id, far))
        assertEquals(FIXED_CLOCK.millis(), store.subscriptions().single().lastSyncedAt)
    }

    @Test
    fun `out-of-window refresh still learns site id`() = runTest {
        source.schedule = ParsedSchedule(null, null, null, emptyList())
        repo.addSubscription(GROUP, "ІПЗс-24-1")
        source.schedule = ParsedSchedule(GROUP, "ІПЗс-24-1", "-607", emptyList())
        repo.refresh(store.subscriptions().single(), DateRange(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)))
        assertEquals("-607", store.subscriptions().single().siteId)
    }

    @Test
    fun `refresh does not overwrite primary flag changed during fetch`() = runTest {
        val first = repo.addSubscription(GROUP, "ІПЗс-24-1")
        val second = repo.addSubscription(GROUP, "ІПЗс-24-2")
        source.onFetch = { repo.setPrimary(second.id) }
        repo.refresh(first)
        assertEquals(listOf(false, true), store.subscriptions().map { it.isPrimary })
    }

    @Test
    fun `refresh of a subscription deleted during fetch stores nothing`() = runTest {
        val first = repo.addSubscription(GROUP, "ІПЗс-24-1")
        repo.addSubscription(GROUP, "ІПЗс-24-2")
        source.schedule = source.schedule.copy(lessons = listOf(lesson(FRI, 1)))
        source.onFetch = { repo.deleteSubscription(first.id) }
        assertTrue(repo.refresh(first).isEmpty())
        assertTrue(store.lessons(first.id, window).isEmpty())
        assertEquals(listOf("ІПЗс-24-2"), store.subscriptions().map { it.name })
    }
}
