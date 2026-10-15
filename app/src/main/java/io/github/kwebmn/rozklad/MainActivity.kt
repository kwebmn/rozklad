package io.github.kwebmn.rozklad

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.github.kwebmn.rozklad.work.Notifier
import io.github.kwebmn.rozklad.work.ScheduleEffects
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kwebmn.rozklad.data.AppSettings
import io.github.kwebmn.rozklad.domain.LessonChange
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.ui.add.AddScreen
import io.github.kwebmn.rozklad.ui.add.AddViewModel
import io.github.kwebmn.rozklad.ui.schedule.ScheduleScreen
import io.github.kwebmn.rozklad.ui.schedule.ScheduleViewModel
import io.github.kwebmn.rozklad.ui.settings.SettingsScreen
import io.github.kwebmn.rozklad.ui.settings.SettingsViewModel
import io.github.kwebmn.rozklad.ui.theme.RozkladTheme
import io.github.kwebmn.rozklad.ui.update.UpdateDialog
import io.github.kwebmn.rozklad.ui.update.UpdateViewModel
import io.github.kwebmn.rozklad.update.ApkInstaller

private enum class Screen { SCHEDULE, ADD, SETTINGS }

class MainActivity : ComponentActivity() {
    /** Пересчитать напоминания/виджет после изменений расписания или настроек. */
    private fun onScheduleChanged() {
        container.appScope.launch { ScheduleEffects.apply(applicationContext) }
    }

    /** Изменения, найденные обновлением в приложении, уведомляются так же, как фоновой синхронизацией. */
    private fun onSynced(sub: Subscription, changes: List<LessonChange>) {
        val c = container
        c.appScope.launch {
            if (changes.isNotEmpty() && c.repository.primary()?.id == sub.id &&
                c.settings.settings.first().changeNotifications
            ) {
                Notifier.showChanges(applicationContext, sub, changes)
            }
            ScheduleEffects.apply(applicationContext)
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { onScheduleChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch { ScheduleEffects.apply(applicationContext) }
        val c = container
        val installer = ApkInstaller(applicationContext)
        setContent {
            val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val subscriptions by c.repository.subscriptions.collectAsStateWithLifecycle(initialValue = null)
            val scheduleVm: ScheduleViewModel = viewModel(factory = viewModelFactory {
                initializer { ScheduleViewModel(c.repository, c.clock, onSynced = ::onSynced) }
            })
            val addVm: AddViewModel = viewModel(factory = viewModelFactory {
                initializer { AddViewModel(c.repository) }
            })
            val settingsVm: SettingsViewModel = viewModel(factory = viewModelFactory {
                initializer { SettingsViewModel(c.repository, c.settings, onScheduleChanged = ::onScheduleChanged) }
            })
            val updateVm: UpdateViewModel = viewModel(factory = viewModelFactory {
                initializer { UpdateViewModel(c.updateChecker, installer) }
            })
            val updateState by updateVm.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) { updateVm.check(force = false) }

            RozkladTheme(settings.theme) {
                Surface(Modifier.fillMaxSize()) {
                    var screen by rememberSaveable { mutableStateOf(Screen.SCHEDULE) }
                    val subs = subscriptions
                    BackHandler(enabled = screen != Screen.SCHEDULE && !subs.isNullOrEmpty()) {
                        screen = Screen.SCHEDULE
                    }
                    val onAdded = { sub: Subscription ->
                        scheduleVm.selectSubscription(sub.id)
                        onScheduleChanged()
                        screen = Screen.SCHEDULE
                    }
                    when {
                        subs == null -> Unit
                        subs.isEmpty() || screen == Screen.ADD -> AddScreen(
                            vm = addVm,
                            onAdded = onAdded,
                            onBack = if (subs.isEmpty()) null else ({ screen = Screen.SCHEDULE }),
                        )
                        screen == Screen.SETTINGS -> SettingsScreen(
                            vm = settingsVm,
                            versionName = BuildConfig.VERSION_NAME,
                            onBack = { screen = Screen.SCHEDULE },
                            onAdd = { screen = Screen.ADD },
                            onCheckUpdates = { updateVm.check(force = true) },
                        )
                        else -> ScheduleScreen(
                            vm = scheduleVm,
                            clock = c.clock,
                            onAdd = { screen = Screen.ADD },
                            onSettings = { screen = Screen.SETTINGS },
                        )
                    }
                    UpdateDialog(
                        state = updateState,
                        onUpdate = updateVm::download,
                        onInstall = installer::install,
                        onDismiss = updateVm::dismiss,
                        onCancelDownload = updateVm::cancelDownload,
                    )
                }
            }
        }
    }
}
