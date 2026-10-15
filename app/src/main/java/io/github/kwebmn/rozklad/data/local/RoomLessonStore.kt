package io.github.kwebmn.rozklad.data.local

import io.github.kwebmn.rozklad.data.LessonStore
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.Subscription
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomLessonStore(private val dao: ScheduleDao) : LessonStore {
    override fun observeSubscriptions(): Flow<List<Subscription>> =
        dao.observeSubscriptions().map { list -> list.map { it.toDomain() } }

    override suspend fun subscriptions(): List<Subscription> = dao.subscriptions().map { it.toDomain() }

    override suspend fun insertSubscription(sub: Subscription): Long =
        dao.insertSubscription(sub.toEntity().copy(id = 0))

    override suspend fun updateSubscription(sub: Subscription) = dao.updateSubscription(sub.toEntity())

    override suspend fun deleteSubscription(id: Long) = dao.deleteSubscription(id)

    override fun observeLessons(subscriptionId: Long, range: DateRange): Flow<List<Lesson>> =
        dao.observeLessons(subscriptionId, range.from.toEpochDay(), range.to.toEpochDay())
            .map { list -> list.map { it.toDomain() } }

    override suspend fun lessons(subscriptionId: Long, range: DateRange): List<Lesson> =
        dao.lessons(subscriptionId, range.from.toEpochDay(), range.to.toEpochDay()).map { it.toDomain() }

    override suspend fun replaceLessons(subscriptionId: Long, range: DateRange, lessons: List<Lesson>) =
        dao.replaceLessons(
            subscriptionId,
            range.from.toEpochDay(),
            range.to.toEpochDay(),
            lessons.map { it.toEntity(subscriptionId) },
        )
}
