package io.github.kwebmn.rozklad.domain

import io.github.kwebmn.rozklad.fakes.FRI
import io.github.kwebmn.rozklad.fakes.THU
import io.github.kwebmn.rozklad.fakes.lesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonDiffTest {
    private val uiux = lesson(FRI, 2, "UI/UX дизайн", room = "к.1ауд.330/1к")

    @Test
    fun `identical schedules have no changes`() {
        assertTrue(diffLessons(listOf(uiux), listOf(uiux), since = THU).isEmpty())
    }

    @Test
    fun `detects added removed and changed`() {
        val moved = uiux.copy(room = "к.1ауд.503")
        val added = lesson(FRI, 3, "Безпека")
        val removed = lesson(THU, 1, "Алгоритми")
        val changes = diffLessons(listOf(removed, uiux), listOf(moved, added), since = THU)
        assertEquals(
            listOf(
                LessonChange.Removed(removed),
                LessonChange.Changed(uiux, moved),
                LessonChange.Added(added),
            ),
            changes,
        )
    }

    @Test
    fun `past days are ignored`() {
        val past = lesson(THU.minusDays(1), 1)
        assertTrue(diffLessons(listOf(past), emptyList(), since = THU).isEmpty())
    }

    @Test
    fun `describes changes in ukrainian`() {
        assertEquals("Пт 16.10, 2 пара: нова — UI/UX дизайн (Пр)", describe(LessonChange.Added(uiux)))
        assertEquals("Пт 16.10, 2 пара: прибрано — UI/UX дизайн", describe(LessonChange.Removed(uiux)))
        assertEquals(
            "Пт 16.10, 2 пара: відмінено — UI/UX дизайн",
            describe(LessonChange.Changed(uiux, uiux.copy(cancelled = true))),
        )
        assertEquals(
            "Пт 16.10, 2 пара: змінено — UI/UX дизайн (ауд. 503)",
            describe(LessonChange.Changed(uiux, uiux.copy(room = "к.1ауд.503"))),
        )
        assertEquals(
            "Пт 16.10, 2 пара: змінено — UI/UX дизайн",
            describe(LessonChange.Changed(uiux, uiux.copy(kind = "Л"))),
        )
    }

    @Test
    fun `summary is capped`() {
        val changes = (1..7).map { LessonChange.Added(lesson(FRI, it)) }
        val summary = changeSummary(changes)
        assertEquals(6, summary.size)
        assertEquals("і ще: 2", summary.last())
    }
}
