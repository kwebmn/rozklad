package io.github.kwebmn.rozklad.domain

import io.github.kwebmn.rozklad.fakes.FRI
import io.github.kwebmn.rozklad.fakes.THU
import io.github.kwebmn.rozklad.fakes.lesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class DayLogicTest {
    private fun at(time: String) = LocalDateTime.parse("2026-10-15T$time")
    private val week = listOf(lesson(THU, 1), lesson(THU, 2), lesson(FRI.plusDays(3), 1))

    @Test
    fun `today while pairs remain`() {
        assertEquals(THU, chooseStartDay(at("07:00"), week))
        assertEquals(THU, chooseStartDay(at("10:30"), week))
    }

    @Test
    fun `next day with pairs after the last one`() {
        assertEquals(FRI.plusDays(3), chooseStartDay(at("11:30"), week))
    }

    @Test
    fun `today when nothing ahead`() {
        assertEquals(THU, chooseStartDay(at("12:00"), listOf(lesson(THU, 1))))
    }

    @Test
    fun `cancelled pairs do not keep today open`() {
        val lessons = listOf(lesson(THU, 4, cancelled = true), lesson(FRI, 1))
        assertEquals(FRI, chooseStartDay(at("09:00"), lessons))
    }

    @Test
    fun `current or next pair`() {
        val day = listOf(lesson(THU, 1), lesson(THU, 2, cancelled = true), lesson(THU, 3))
        assertEquals(1, currentOrNext(at("09:00"), day)?.pairNo)
        assertEquals(3, currentOrNext(at("10:30"), day)?.pairNo)
        assertNull(currentOrNext(at("14:00"), day))
    }

    @Test
    fun `day rows mark windows between pairs`() {
        val rows = buildDayRows(listOf(lesson(THU, 3), lesson(THU, 1), lesson(THU, 1, "Інше", slot = 1)))
        assertEquals(3, rows.size)
        val first = rows[0] as DayRow.Lessons
        assertEquals(listOf("Предмет", "Інше"), first.lessons.map { it.subject })
        assertTrue(rows[1] is DayRow.Window)
        assertEquals(2, rows[1].pairNo)
        assertEquals(3, rows[2].pairNo)
        assertTrue(buildDayRows(emptyList()).isEmpty())
    }
}
