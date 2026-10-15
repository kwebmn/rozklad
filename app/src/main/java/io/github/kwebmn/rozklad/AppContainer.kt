package io.github.kwebmn.rozklad

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.time.Clock
import java.util.concurrent.TimeUnit

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class AppContainer(context: Context) {
    val clock: Clock = Clock.systemDefaultZone()
    /** Ошибка фоновой задачи (например, ScheduleEffects из BootReceiver) не должна ронять процесс. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, e -> Log.e("Rozklad", "Background task failed", e) },
    )

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
