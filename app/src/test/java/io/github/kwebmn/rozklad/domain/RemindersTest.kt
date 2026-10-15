package io.github.kwebmn.rozklad.domain

import io.github.kwebmn.rozklad.fakes.FRI
import io.github.kwebmn.rozklad.fakes.THU
import io.github.kwebmn.rozklad.fakes.lesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class RemindersTest {
    private val now = LocalDateTime.parse("2026-10-15T09:00")

    @Test
    fun `schedules future pairs within 24 hours`() {
        val lessons = listOf(
            lesson(THU, 1), lesson(THU, 2), lesson(THU, 3, kind = "Л"),
            lesson(FRI, 1), lesson(FRI, 2),
        )
        val r = upcomingReminders(lessons, now, minutesBefore = 10)
        assertEquals(
            listOf("2026-10-15T09:50", "2026-10-15T11:50", "2026-10-16T08:20").map(LocalDateTime::parse),
            r.map { it.triggerAt },
        )
        assertEquals("Через 10 хв: Предмет", r[0].title)
        assertEquals("Пр · ауд. 410", r[0].text)
        assertEquals(reminderId(THU, 2), r[0].id)
    }

    @Test
    fun `pair starting sooner than the offset gets no reminder`() {
        val soon = lesson(THU, 2).copy(start = LocalTime.of(9, 5))
        assertTrue(upcomingReminders(listOf(soon), now, minutesBefore = 10).isEmpty())
    }

    @Test
    fun `cancelled pairs are skipped and slots are merged`() {
        val lessons = listOf(
            lesson(THU, 2, "Відмінена", cancelled = true),
            lesson(THU, 2, "Заміна", slot = 1, room = null, remote = true, kind = "Л"),
            lesson(THU, 3, cancelled = true),
        )
        val r = upcomingReminders(lessons, now, minutesBefore = 10)
        assertEquals(1, r.size)
        assertEquals("Через 10 хв: Заміна", r[0].title)
        assertEquals("Л · дистанційно", r[0].text)
    }

    @Test
    fun `ids are unique per day and pair`() {
        assertTrue(reminderId(THU, 2) != reminderId(FRI, 2))
        assertTrue(reminderId(THU, 2) != reminderId(THU, 3))
    }
}
