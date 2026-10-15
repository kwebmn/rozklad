package io.github.kwebmn.rozklad.fakes

import io.github.kwebmn.rozklad.data.LessonStore
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.lessonOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeLessonStore : LessonStore {
    val subs = MutableStateFlow<List<Subscription>>(emptyList())
    val lessonsBySub = MutableStateFlow<Map<Long, List<Lesson>>>(emptyMap())
    private var nextId = 1L

    override fun observeSubscriptions(): Flow<List<Subscription>> = subs
    override suspend fun subscriptions(): List<Subscription> = subs.value

    override suspend fun insertSubscription(sub: Subscription): Long {
        val id = nextId++
        subs.value = subs.value + sub.copy(id = id)
        return id
    }

    override suspend fun updateSubscription(sub: Subscription) {
        subs.value = subs.value.map { if (it.id == sub.id) sub else it }
    }

    override suspend fun markSynced(id: Long, syncedAt: Long?, siteId: String?) {
        subs.value = subs.value.map {
            if (it.id == id) it.copy(lastSyncedAt = syncedAt ?: it.lastSyncedAt, siteId = it.siteId ?: siteId) else it
        }
    }

    override suspend fun deleteSubscription(id: Long) {
        subs.value = subs.value.filterNot { it.id == id }
        lessonsBySub.value = lessonsBySub.value - id
    }

    override fun observeLessons(subscriptionId: Long, range: DateRange): Flow<List<Lesson>> =
        lessonsBySub.map { all -> select(all, subscriptionId, range) }

    override suspend fun lessons(subscriptionId: Long, range: DateRange): List<Lesson> =
        select(lessonsBySub.value, subscriptionId, range)

    override suspend fun replaceLessons(subscriptionId: Long, range: DateRange, lessons: List<Lesson>) {
        val kept = lessonsBySub.value[subscriptionId].orEmpty().filterNot { it.date in range }
        lessonsBySub.value = lessonsBySub.value + (subscriptionId to kept + lessons)
    }

    private fun select(all: Map<Long, List<Lesson>>, id: Long, range: DateRange) =
        all[id].orEmpty().filter { it.date in range }.sortedWith(lessonOrder)
}
