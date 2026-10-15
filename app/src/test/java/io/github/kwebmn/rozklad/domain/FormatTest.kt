package io.github.kwebmn.rozklad.domain

import io.github.kwebmn.rozklad.fakes.FIXED_CLOCK
import io.github.kwebmn.rozklad.fakes.KYIV
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

class FormatTest {
    private val now = FIXED_CLOCK.millis()
    private val minute = 60_000L

    @Test
    fun `day names`() {
        assertEquals("Чт", dayShort(DayOfWeek.THURSDAY))
        assertEquals("П'ятниця", dayFull(DayOfWeek.FRIDAY))
        assertEquals("16.10", shortDate(LocalDate.of(2026, 10, 16)))
        assertEquals("08:30", formatTime(LocalTime.of(8, 30)))
    }

    @Test
    fun `rooms are shortened`() {
        assertEquals("ауд. 330/5к", shortRoom("к.1ауд.330/5к"))
        assertEquals("ауд. радіостудія", shortRoom("к.1ауд.радіостудія"))
        assertEquals("к.2, ауд. 101", shortRoom("к.2ауд.101"))
        assertEquals("спортзал", shortRoom("спортзал"))
    }

    @Test
    fun `teachers are shortened to initials`() {
        assertEquals("Демчина М.М.", shortTeacher("Демчина Микола Миколайович"))
        assertEquals("Іванов", shortTeacher("Іванов"))
    }

    @Test
    fun `sync status`() {
        assertEquals("Ще не оновлювалось", formatSyncStatus(null, now, offline = false, zone = KYIV))
        assertEquals("Оновлено щойно", formatSyncStatus(now - 30_000, now, false, KYIV))
        assertEquals("Оновлено 5 хв тому", formatSyncStatus(now - 5 * minute, now, false, KYIV))
        assertEquals("Оновлено 3 год тому", formatSyncStatus(now - 180 * minute, now, false, KYIV))
        assertEquals("Оновлено 13.10 09:00", formatSyncStatus(now - 2 * 24 * 60 * minute, now, false, KYIV))
        assertEquals("Офлайн · дані від 15.10 08:55", formatSyncStatus(now - 5 * minute, now, true, KYIV))
        assertEquals("Офлайн · немає даних", formatSyncStatus(null, now, true, KYIV))
    }

    @Test
    fun `dates and times use latin digits whatever the default locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
            assertEquals("16.10", shortDate(LocalDate.of(2026, 10, 16)))
            assertEquals("08:30", formatTime(LocalTime.of(8, 30)))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
