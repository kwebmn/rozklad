package io.github.kwebmn.rozklad

import android.app.Application

class RozkladApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
