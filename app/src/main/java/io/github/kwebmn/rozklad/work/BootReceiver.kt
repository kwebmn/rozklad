package io.github.kwebmn.rozklad.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.kwebmn.rozklad.container
import kotlinx.coroutines.launch

/** BOOT_COMPLETED, обновление приложения, смена времени/пояса — будильники нужно поставить заново. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        app.container.appScope.launch {
            try {
                ScheduleEffects.apply(app)
            } finally {
                pending.finish()
            }
        }
    }
}
