package io.github.kwebmn.rozklad.domain

import java.time.LocalDate

sealed interface LessonChange {
    val lesson: Lesson

    data class Added(override val lesson: Lesson) : LessonChange
    data class Removed(override val lesson: Lesson) : LessonChange
    data class Changed(val old: Lesson, val new: Lesson) : LessonChange {
        override val lesson: Lesson get() = new
    }
}

private data class LessonKey(val date: LocalDate, val pairNo: Int, val slot: Int)

private fun Lesson.key() = LessonKey(date, pairNo, slot)

fun diffLessons(old: List<Lesson>, new: List<Lesson>, since: LocalDate): List<LessonChange> {
    val before = old.filter { !it.date.isBefore(since) }.associateBy { it.key() }
    val after = new.filter { !it.date.isBefore(since) }.associateBy { it.key() }
    val keys = (before.keys + after.keys).sortedWith(compareBy({ it.date }, { it.pairNo }, { it.slot }))
    return keys.mapNotNull { key ->
        val a = before[key]
        val b = after[key]
        when {
            a == null -> LessonChange.Added(b!!)
            b == null -> LessonChange.Removed(a)
            a != b -> LessonChange.Changed(a, b)
            else -> null
        }
    }
}

fun describe(change: LessonChange): String {
    val l = change.lesson
    val prefix = "${dayShort(l.date.dayOfWeek)} ${shortDate(l.date)}, ${l.pairNo} пара"
    return when (change) {
        is LessonChange.Added -> "$prefix: нова — ${l.subject} (${l.kind})"
        is LessonChange.Removed -> "$prefix: прибрано — ${l.subject}"
        is LessonChange.Changed -> {
            if (change.new.cancelled && !change.old.cancelled) {
                "$prefix: відмінено — ${l.subject}"
            } else {
                val details = listOfNotNull(
                    change.new.room?.takeIf { it != change.old.room }?.let(::shortRoom),
                    change.new.teacher?.takeIf { it != change.old.teacher }?.let(::shortTeacher),
                )
                val suffix = if (details.isEmpty()) "" else " (${details.joinToString(", ")})"
                "$prefix: змінено — ${l.subject}$suffix"
            }
        }
    }
}

fun changeSummary(changes: List<LessonChange>, max: Int = 5): List<String> {
    val lines = changes.take(max).map(::describe)
    return if (changes.size > max) lines + "і ще: ${changes.size - max}" else lines
}
