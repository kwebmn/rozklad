package io.github.kwebmn.rozklad.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.kwebmn.rozklad.container
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.MAX_PAIR
import io.github.kwebmn.rozklad.domain.Reminder
import io.github.kwebmn.rozklad.domain.reminderId
import io.github.kwebmn.rozklad.domain.upcomingReminders
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime

object ReminderScheduler {
    suspend fun reschedule(context: Context) {
        val c = context.container
        val alarms = context.getSystemService(AlarmManager::class.java)
        val now = LocalDateTime.now(c.clock)
        val today = now.toLocalDate()

        for (dayOffset in -1L..1L) {
            for (pair in 1..MAX_PAIR) cancel(context, alarms, reminderId(today.plusDays(dayOffset), pair))
        }

        val settings = c.settings.settings.first()
        if (!settings.remindersEnabled) return
        val primary = c.repository.primary() ?: return
        val lessons = c.repository.lessonsOnce(primary.id, DateRange(today, today.plusDays(1)))
        upcomingReminders(lessons, now, settings.reminderMinutes).forEach { reminder ->
            val at = reminder.triggerAt.atZone(c.clock.zone).toInstant().toEpochMilli()
            val intent = pendingIntent(context, reminder)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
        }
    }

    private fun pendingIntent(context: Context, reminder: Reminder): PendingIntent = PendingIntent.getBroadcast(
        context,
        reminder.id,
        Intent(context, ReminderReceiver::class.java)
            .putExtra(ReminderReceiver.EXTRA_ID, reminder.id)
            .putExtra(ReminderReceiver.EXTRA_TITLE, reminder.title)
            .putExtra(ReminderReceiver.EXTRA_TEXT, reminder.text),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancel(context: Context, alarms: AlarmManager, id: Int) {
        PendingIntent.getBroadcast(
            context,
            id,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        )?.let {
            alarms.cancel(it)
            it.cancel()
        }
    }
}
