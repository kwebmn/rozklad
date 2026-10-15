package io.github.kwebmn.rozklad

import android.app.Application
import io.github.kwebmn.rozklad.work.Notifier

class RozkladApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.createChannels(this)
    }
}
