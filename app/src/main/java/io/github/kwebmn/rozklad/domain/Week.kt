package io.github.kwebmn.rozklad.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

fun weekStart(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

fun syncWindow(today: LocalDate): DateRange {
    val start = weekStart(today)
    return DateRange(start, start.plusDays(13))
}
