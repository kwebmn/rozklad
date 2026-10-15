package io.github.kwebmn.rozklad.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.SubscriptionType
import java.time.LocalDate
import java.time.LocalTime

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val name: String,
    val siteId: String?,
    val isPrimary: Boolean,
    val lastSyncedAt: Long?,
)

@Entity(
    tableName = "lessons",
    primaryKeys = ["subscriptionId", "date", "pairNo", "slot"],
    indices = [Index("subscriptionId")],
)
data class LessonEntity(
    val subscriptionId: Long,
    val date: Long,
    val pairNo: Int,
    val slot: Int,
    val start: String,
    val end: String,
    val subject: String,
    val kind: String,
    val teacher: String?,
    val room: String?,
    val stream: String?,
    val note: String?,
    val cancelled: Boolean,
    val remote: Boolean,
    val links: String,
)

fun Subscription.toEntity() = SubscriptionEntity(id, type.name, name, siteId, isPrimary, lastSyncedAt)

fun SubscriptionEntity.toDomain() =
    Subscription(id, SubscriptionType.valueOf(type), name, siteId, isPrimary, lastSyncedAt)

fun Lesson.toEntity(subscriptionId: Long) = LessonEntity(
    subscriptionId = subscriptionId,
    date = date.toEpochDay(),
    pairNo = pairNo,
    slot = slot,
    start = start.toString(),
    end = end.toString(),
    subject = subject,
    kind = kind,
    teacher = teacher,
    room = room,
    stream = stream,
    note = note,
    cancelled = cancelled,
    remote = remote,
    links = links.joinToString("\n"),
)

fun LessonEntity.toDomain() = Lesson(
    date = LocalDate.ofEpochDay(date),
    pairNo = pairNo,
    slot = slot,
    start = LocalTime.parse(start),
    end = LocalTime.parse(end),
    subject = subject,
    kind = kind,
    teacher = teacher,
    room = room,
    stream = stream,
    note = note,
    cancelled = cancelled,
    remote = remote,
    links = links.split("\n").filter { it.isNotEmpty() },
)
