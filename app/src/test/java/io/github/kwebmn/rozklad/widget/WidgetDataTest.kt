package io.github.kwebmn.rozklad.widget

import io.github.kwebmn.rozklad.fakes.FRI
import io.github.kwebmn.rozklad.fakes.THU
import io.github.kwebmn.rozklad.fakes.lesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class WidgetDataTest {
    private fun at(time: String) = LocalDateTime.parse("2026-10-15T$time")
    private val lessons = listOf(
        lesson(THU, 2, "Менеджмент", room = "к.1ауд.330/5к"),
        lesson(THU, 1, "Prompt"),
        lesson(FRI, 1, "Іноземна", room = null, remote = true),
    )

    @Test
    fun `today with the current pair highlighted`() {
        val data = buildWidgetData("ІПЗс-24-1", lessons, at("10:30"))
        assertEquals("ІПЗс-24-1 · Четвер", data.title)
        assertEquals(listOf("08:30", "10:00"), data.rows.map { it.time })
        assertEquals(listOf(false, true), data.rows.map { it.highlight })
        assertEquals("ауд. 330/5к", data.rows[1].room)
        assertNull(data.empty)
    }

    @Test
    fun `switches to the next day after the last pair`() {
        val data = buildWidgetData("ІПЗс-24-1", lessons, at("12:00"))
        assertEquals("ІПЗс-24-1 · П'ятниця 16.10", data.title)
        assertEquals("дист.", data.rows.single().room)
        assertEquals(true, data.rows.single().highlight)
    }

    @Test
    fun `no subscription asks to open the app`() {
        val data = buildWidgetData(null, emptyList(), at("10:00"))
        assertEquals("Розклад", data.title)
        assertEquals("Відкрийте застосунок, щоб обрати групу", data.empty)
    }

    @Test
    fun `no lessons says so`() {
        assertEquals("Пар немає", buildWidgetData("ІПЗс-24-1", emptyList(), at("10:00")).empty)
    }
}
