package io.github.kwebmn.rozklad.work

import android.content.Context

/** Всё, что надо пересчитать после изменения расписания или настроек. */
object ScheduleEffects {
    suspend fun apply(context: Context) {
        ReminderScheduler.reschedule(context)
    }
}
