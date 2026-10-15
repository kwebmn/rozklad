package io.github.kwebmn.rozklad.data

import io.github.kwebmn.rozklad.data.remote.ScheduleSource
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.LessonChange
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.domain.diffLessons
import io.github.kwebmn.rozklad.domain.syncWindow
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

class ScheduleRepository(
    private val source: ScheduleSource,
    private val store: LessonStore,
    private val clock: Clock,
) {
    val subscriptions: Flow<List<Subscription>> = store.observeSubscriptions()

    private fun today(): LocalDate = LocalDate.now(clock)

    fun lessons(subscriptionId: Long, range: DateRange): Flow<List<Lesson>> =
        store.observeLessons(subscriptionId, range)

    suspend fun lessonsOnce(subscriptionId: Long, range: DateRange): List<Lesson> =
        store.lessons(subscriptionId, range)

    suspend fun subscriptionsOnce(): List<Subscription> = store.subscriptions()

    suspend fun primary(): Subscription? {
        val all = store.subscriptions()
        return all.firstOrNull { it.isPrimary } ?: all.firstOrNull()
    }

    suspend fun suggest(type: SubscriptionType, query: String, faculty: Int, course: Int): List<String> =
        source.suggest(type, query, faculty, course)

    suspend fun addSubscription(type: SubscriptionType, name: String): Subscription {
        val existing = store.subscriptions()
        existing.firstOrNull { it.type == type && it.name == name }?.let { return it }
        val range = syncWindow(today())
        val parsed = source.fetch(type, siteId = null, name = name, range = range)
        val sub = Subscription(
            type = type,
            name = name,
            siteId = parsed.siteId,
            isPrimary = existing.isEmpty(),
            lastSyncedAt = clock.millis(),
        )
        val id = store.insertSubscription(sub)
        store.replaceLessons(id, range, parsed.lessons)
        return sub.copy(id = id)
    }

    /**
     * Бросает NetworkException / SiteFormatException; при ошибке кэш не меняется.
     * Только обновление стандартного окна синхронизации сдвигает lastSyncedAt и возвращает изменения;
     * подгрузка других недель лишь сохраняет пары.
     */
    suspend fun refresh(sub: Subscription, range: DateRange = syncWindow(today())): List<LessonChange> {
        val current = store.subscriptions().firstOrNull { it.id == sub.id } ?: return emptyList()
        val parsed = source.fetch(current.type, current.siteId, current.name, range)
        // Подписку могли удалить во время запроса — тогда ничего не пишем, чтобы не оставить сирот.
        if (store.subscriptions().none { it.id == current.id }) return emptyList()
        val isWindow = range == syncWindow(today())
        val old = store.lessons(current.id, range)
        store.replaceLessons(current.id, range, parsed.lessons)
        store.markSynced(current.id, syncedAt = if (isWindow) clock.millis() else null, siteId = parsed.siteId)
        val last = current.lastSyncedAt
        if (!isWindow || last == null) return emptyList()
        // Даты за пределами прошлого окна загружены впервые — это не изменения.
        val coveredTo = syncWindow(Instant.ofEpochMilli(last).atZone(clock.zone).toLocalDate()).to
        return diffLessons(
            old.filter { !it.date.isAfter(coveredTo) },
            parsed.lessons.filter { !it.date.isAfter(coveredTo) },
            since = today(),
        )
    }

    suspend fun deleteSubscription(id: Long) {
        val all = store.subscriptions()
        val target = all.firstOrNull { it.id == id } ?: return
        store.deleteSubscription(id)
        if (target.isPrimary) {
            all.firstOrNull { it.id != id }?.let { store.updateSubscription(it.copy(isPrimary = true)) }
        }
    }

    suspend fun setPrimary(id: Long) {
        store.subscriptions().forEach { sub ->
            val wanted = sub.id == id
            if (sub.isPrimary != wanted) store.updateSubscription(sub.copy(isPrimary = wanted))
        }
    }
}
