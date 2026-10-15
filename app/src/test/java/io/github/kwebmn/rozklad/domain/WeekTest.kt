package io.github.kwebmn.rozklad.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeekTest {
    @Test
    fun `week starts on monday`() {
        assertEquals(LocalDate.of(2026, 10, 12), weekStart(LocalDate.of(2026, 10, 14)))
        assertEquals(LocalDate.of(2026, 10, 12), weekStart(LocalDate.of(2026, 10, 12)))
        assertEquals(LocalDate.of(2026, 10, 12), weekStart(LocalDate.of(2026, 10, 18)))
    }

    @Test
    fun `sync window covers two weeks from monday`() {
        assertEquals(
            DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 25)),
            syncWindow(LocalDate.of(2026, 10, 15)),
        )
    }

    @Test
    fun `date range contains is inclusive`() {
        val range = DateRange(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 18))
        assertTrue(LocalDate.of(2026, 10, 12) in range)
        assertTrue(LocalDate.of(2026, 10, 18) in range)
        assertFalse(LocalDate.of(2026, 10, 19) in range)
    }
}
