package io.github.kwebmn.rozklad.widget

import android.content.Context
import io.github.kwebmn.rozklad.container
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.chooseStartDay
import io.github.kwebmn.rozklad.domain.currentOrNext
import io.github.kwebmn.rozklad.domain.dayFull
import io.github.kwebmn.rozklad.domain.formatTime
import io.github.kwebmn.rozklad.domain.lessonOrder
import io.github.kwebmn.rozklad.domain.shortDate
import io.github.kwebmn.rozklad.domain.shortRoom
import java.time.LocalDateTime

data class WidgetRow(
    val time: String,
    val subject: String,
    val room: String,
    val highlight: Boolean,
    val cancelled: Boolean,
)

data class WidgetData(val title: String, val rows: List<WidgetRow>, val empty: String?)

fun buildWidgetData(subscriptionName: String?, lessons: List<Lesson>, now: LocalDateTime): WidgetData {
    if (subscriptionName == null) {
        return WidgetData("Розклад", emptyList(), "Відкрийте застосунок, щоб обрати групу")
    }
    val day = chooseStartDay(now, lessons)
    val dayLessons = lessons.filter { it.date == day }.sortedWith(lessonOrder)
    val next = currentOrNext(now, dayLessons)
    val title = buildString {
        append(subscriptionName).append(" · ").append(dayFull(day.dayOfWeek))
        if (day != now.toLocalDate()) append(" ").append(shortDate(day))
    }
    val rows = dayLessons.map { l ->
        WidgetRow(
            time = formatTime(l.start),
            subject = l.subject,
            room = l.room?.let(::shortRoom) ?: if (l.remote) "дист." else "",
            highlight = l == next,
            cancelled = l.cancelled,
        )
    }
    return WidgetData(title, rows, if (rows.isEmpty()) "Пар немає" else null)
}

suspend fun loadWidgetData(context: Context): WidgetData {
    val c = context.container
    val now = LocalDateTime.now(c.clock)
    val sub = c.repository.primary()
    val lessons = sub?.let {
        c.repository.lessonsOnce(it.id, DateRange(now.toLocalDate(), now.toLocalDate().plusDays(7)))
    }.orEmpty()
    return buildWidgetData(sub?.name, lessons, now)
}
