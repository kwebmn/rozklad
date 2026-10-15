package io.github.kwebmn.rozklad.work

import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.data.SettingsStore
import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.data.remote.SiteFormatException
import io.github.kwebmn.rozklad.domain.LessonChange
import io.github.kwebmn.rozklad.domain.Subscription
import kotlinx.coroutines.flow.first

class SyncRunner(
    private val repo: ScheduleRepository,
    private val settings: SettingsStore,
    private val notify: (Subscription, List<LessonChange>) -> Unit,
) {
    /** Обновляет все подписки. @return true, если хоть одна упала из-за сети и нужен retry. */
    suspend fun run(): Boolean {
        val notifyChanges = settings.settings.first().changeNotifications
        var networkFailed = false
        for (sub in repo.subscriptionsOnce()) {
            try {
                val changes = repo.refresh(sub)
                if (sub.isPrimary && notifyChanges && changes.isNotEmpty()) notify(sub, changes)
            } catch (e: NetworkException) {
                networkFailed = true
            } catch (e: SiteFormatException) {
                // Кэш не тронут; следующий периодический запуск попробует снова.
            }
        }
        return networkFailed
    }
}
