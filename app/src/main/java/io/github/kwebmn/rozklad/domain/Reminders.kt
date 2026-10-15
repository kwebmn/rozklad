package io.github.kwebmn.rozklad.domain

import java.time.LocalDate
import java.time.LocalDateTime

const val MAX_PAIR = 8

data class Reminder(
    val id: Int,
    val triggerAt: LocalDateTime,
    val title: String,
    val text: String,
)

fun reminderId(date: LocalDate, pairNo: Int): Int = (date.toEpochDay() * 16 + pairNo).toInt()

fun upcomingReminders(
    lessons: List<Lesson>,
    now: LocalDateTime,
    minutesBefore: Int,
    horizonHours: Long = 24,
): List<Reminder> {
    val horizon = now.plusHours(horizonHours)
    return lessons
        .filter { !it.cancelled }
        .groupBy { it.date to it.pairNo }
        .values
        .mapNotNull { slots ->
            val l = slots.minBy { it.slot }
            val trigger = l.date.atTime(l.start).minusMinutes(minutesBefore.toLong())
            if (!trigger.isAfter(now) || trigger.isAfter(horizon)) return@mapNotNull null
            val where = l.room?.let(::shortRoom) ?: if (l.remote) "дистанційно" else null
            Reminder(
                id = reminderId(l.date, l.pairNo),
                triggerAt = trigger,
                title = "Через $minutesBefore хв: ${l.subject}",
                text = listOfNotNull(l.kind.ifEmpty { null }, where).joinToString(" · "),
            )
        }
        .sortedBy { it.triggerAt }
}
