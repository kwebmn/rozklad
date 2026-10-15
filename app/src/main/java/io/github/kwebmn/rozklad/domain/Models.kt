package io.github.kwebmn.rozklad.domain

import java.time.LocalDate
import java.time.LocalTime

enum class SubscriptionType { GROUP, TEACHER }

data class Subscription(
    val id: Long = 0,
    val type: SubscriptionType,
    val name: String,
    val siteId: String?,
    val isPrimary: Boolean = false,
    val lastSyncedAt: Long? = null,
)

data class Lesson(
    val date: LocalDate,
    val pairNo: Int,
    val slot: Int,
    val start: LocalTime,
    val end: LocalTime,
    val subject: String,
    val kind: String,
    val teacher: String? = null,
    val room: String? = null,
    val stream: String? = null,
    val note: String? = null,
    val cancelled: Boolean = false,
    val remote: Boolean = false,
    val links: List<String> = emptyList(),
)

data class DateRange(val from: LocalDate, val to: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(from) && !date.isAfter(to)
}

val lessonOrder: Comparator<Lesson> = compareBy({ it.date }, { it.pairNo }, { it.slot })
