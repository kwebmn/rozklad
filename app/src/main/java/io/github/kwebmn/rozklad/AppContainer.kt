package io.github.kwebmn.rozklad

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.github.kwebmn.rozklad.data.DataStoreSettings
import io.github.kwebmn.rozklad.data.LessonStore
import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.data.SettingsStore
import io.github.kwebmn.rozklad.data.UpdateChecker
import io.github.kwebmn.rozklad.data.local.AppDatabase
import io.github.kwebmn.rozklad.data.local.RoomLessonStore
import io.github.kwebmn.rozklad.data.remote.ScheduleApi
import io.github.kwebmn.rozklad.data.remote.UpdateApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.time.Clock
import java.util.concurrent.TimeUnit

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class AppContainer(context: Context) {
    val clock: Clock = Clock.systemDefaultZone()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    internal val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val database = AppDatabase.build(context)
    val store: LessonStore = RoomLessonStore(database.dao())
    val repository = ScheduleRepository(ScheduleApi(http), store, clock)
    val settings: SettingsStore = DataStoreSettings(context.settingsDataStore)
    val updateChecker = UpdateChecker(UpdateApi(http), settings, BuildConfig.VERSION_NAME, clock)
}

val Context.container: AppContainer
    get() = (applicationContext as RozkladApp).container
