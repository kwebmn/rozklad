package io.github.kwebmn.rozklad.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Сегодня, если ещё есть неотменённые пары; иначе ближайший следующий день с парами; иначе сегодня. */
fun chooseStartDay(now: LocalDateTime, lessons: List<Lesson>): LocalDate {
    val today = now.toLocalDate()
    val active = lessons.filter { !it.cancelled }
    val todayEnd = active.filter { it.date == today }.maxOfOrNull { it.end }
    if (todayEnd != null && now.toLocalTime().isBefore(todayEnd)) return today
    return active.map { it.date }.filter { it.isAfter(today) }.minOrNull() ?: today
}

/** Идущая сейчас или ближайшая неотменённая пара дня. */
fun currentOrNext(now: LocalDateTime, dayLessons: List<Lesson>): Lesson? =
    dayLessons
        .filter { !it.cancelled && it.date.atTime(it.end).isAfter(now) }
        .minWithOrNull(compareBy({ it.date.atTime(it.start) }, { it.slot }))

sealed interface DayRow {
    val pairNo: Int

    data class Lessons(
        override val pairNo: Int,
        val start: LocalTime,
        val end: LocalTime,
        val lessons: List<Lesson>,
    ) : DayRow

    data class Window(override val pairNo: Int) : DayRow
}

fun buildDayRows(dayLessons: List<Lesson>): List<DayRow> {
    if (dayLessons.isEmpty()) return emptyList()
    val byPair = dayLessons.groupBy { it.pairNo }
    return (byPair.keys.min()..byPair.keys.max()).map { pair ->
        val lessons = byPair[pair]?.sortedBy { it.slot }
        if (lessons == null) DayRow.Window(pair)
        else DayRow.Lessons(pair, lessons.first().start, lessons.first().end, lessons)
    }
}
