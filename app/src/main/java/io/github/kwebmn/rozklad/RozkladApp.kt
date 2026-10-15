package io.github.kwebmn.rozklad

import android.app.Application
import io.github.kwebmn.rozklad.work.Notifier
import io.github.kwebmn.rozklad.work.SyncWorker

class RozkladApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.createChannels(this)
        SyncWorker.schedule(this)
    }
}
