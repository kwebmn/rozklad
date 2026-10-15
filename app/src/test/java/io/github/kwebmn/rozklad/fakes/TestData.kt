package io.github.kwebmn.rozklad.fakes

import io.github.kwebmn.rozklad.domain.Lesson
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

val KYIV: ZoneId = ZoneId.of("Europe/Kyiv")

/** Часы, остановленные на местном времени Киева, формат "2026-10-15T09:00". */
fun clockAt(local: String): Clock =
    Clock.fixed(LocalDateTime.parse(local).atZone(KYIV).toInstant(), KYIV)

/** Четверг 15.10.2026, 09:00 по Киеву. */
val FIXED_CLOCK: Clock = clockAt("2026-10-15T09:00")
val THU: LocalDate = LocalDate.of(2026, 10, 15)
val FRI: LocalDate = LocalDate.of(2026, 10, 16)

private val PAIR_TIMES = mapOf(
    1 to ("08:30" to "09:50"), 2 to ("10:00" to "11:20"), 3 to ("12:00" to "13:20"),
    4 to ("13:30" to "14:50"), 5 to ("15:10" to "16:30"), 6 to ("16:40" to "18:00"),
    7 to ("18:10" to "19:30"),
)

fun lesson(
    date: LocalDate,
    pair: Int,
    subject: String = "Предмет",
    slot: Int = 0,
    kind: String = "Пр",
    room: String? = "к.1ауд.410",
    teacher: String? = "Демчина Микола Миколайович",
    cancelled: Boolean = false,
    remote: Boolean = false,
): Lesson {
    val (start, end) = PAIR_TIMES.getValue(pair)
    return Lesson(
        date = date, pairNo = pair, slot = slot,
        start = LocalTime.parse(start), end = LocalTime.parse(end),
        subject = subject, kind = kind, teacher = teacher, room = room,
        note = if (cancelled) "Увага! Заняття відмінено!" else null,
        cancelled = cancelled, remote = remote,
    )
}
