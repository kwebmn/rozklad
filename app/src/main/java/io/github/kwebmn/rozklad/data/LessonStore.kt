package io.github.kwebmn.rozklad.data

import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.Subscription
import kotlinx.coroutines.flow.Flow

interface LessonStore {
    fun observeSubscriptions(): Flow<List<Subscription>>
    suspend fun subscriptions(): List<Subscription>
    suspend fun insertSubscription(sub: Subscription): Long
    suspend fun updateSubscription(sub: Subscription)
    /**
     * Точечно отмечает синхронизацию, не трогая остальные поля строки:
     * lastSyncedAt меняется только при syncedAt != null, siteId заполняется только если он ещё null.
     */
    suspend fun markSynced(id: Long, syncedAt: Long?, siteId: String?)
    /** Удаляет подписку вместе с её парами. */
    suspend fun deleteSubscription(id: Long)
    fun observeLessons(subscriptionId: Long, range: DateRange): Flow<List<Lesson>>
    suspend fun lessons(subscriptionId: Long, range: DateRange): List<Lesson>
    /** Атомарно заменяет все пары подписки в периоде. */
    suspend fun replaceLessons(subscriptionId: Long, range: DateRange, lessons: List<Lesson>)
}
