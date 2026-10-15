package io.github.kwebmn.rozklad.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

private val DAY_SHORT = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд")
private val DAY_FULL = listOf("Понеділок", "Вівторок", "Середа", "Четвер", "П'ятниця", "Субота", "Неділя")
private val ROOM = Regex("""^к\.(\d+)ауд\.(.+)$""")

fun dayShort(day: DayOfWeek): String = DAY_SHORT[day.value - 1]

fun dayFull(day: DayOfWeek): String = DAY_FULL[day.value - 1]

fun shortDate(date: LocalDate): String = "%02d.%02d".format(Locale.ROOT, date.dayOfMonth, date.monthValue)

fun formatTime(time: LocalTime): String = "%02d:%02d".format(Locale.ROOT, time.hour, time.minute)

fun shortRoom(room: String): String {
    val match = ROOM.find(room.trim()) ?: return room.trim()
    val (building, number) = match.destructured
    return if (building == "1") "ауд. $number" else "к.$building, ауд. $number"
}

fun shortTeacher(full: String): String {
    val parts = full.trim().split(Regex("\\s+"))
    if (parts.size < 2) return full.trim()
    return parts[0] + " " + parts.drop(1).take(2).joinToString("") { "${it.first()}." }
}

fun formatSyncStatus(lastSyncedAt: Long?, nowMillis: Long, offline: Boolean, zone: ZoneId): String {
    if (lastSyncedAt == null) return if (offline) "Офлайн · немає даних" else "Ще не оновлювалось"
    val at = Instant.ofEpochMilli(lastSyncedAt).atZone(zone)
    val stamp = "${shortDate(at.toLocalDate())} ${formatTime(at.toLocalTime())}"
    if (offline) return "Офлайн · дані від $stamp"
    val minutes = (nowMillis - lastSyncedAt) / 60_000
    return when {
        minutes < 1 -> "Оновлено щойно"
        minutes < 60 -> "Оновлено $minutes хв тому"
        minutes < 24 * 60 -> "Оновлено ${minutes / 60} год тому"
        else -> "Оновлено $stamp"
    }
}
