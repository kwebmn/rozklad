package io.github.kwebmn.rozklad.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import io.github.kwebmn.rozklad.widget.RozkladWidget

/** Всё, что надо пересчитать после изменения расписания или настроек. */
object ScheduleEffects {
    suspend fun apply(context: Context) {
        ReminderScheduler.reschedule(context)
        RozkladWidget().updateAll(context)
    }
}
