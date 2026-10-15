package io.github.kwebmn.rozklad.data

import io.github.kwebmn.rozklad.data.remote.ReleaseInfo
import io.github.kwebmn.rozklad.data.remote.ReleaseSource
import io.github.kwebmn.rozklad.domain.isNewer
import kotlinx.coroutines.flow.first
import java.time.Clock

class UpdateChecker(
    private val source: ReleaseSource,
    private val settings: SettingsStore,
    private val currentVersion: String,
    private val clock: Clock,
) {
    /** Новый релиз или null. Без force — не чаще раза в сутки. Бросает NetworkException. */
    suspend fun check(force: Boolean): ReleaseInfo? {
        val last = settings.settings.first().lastUpdateCheck
        if (!force && clock.millis() - last < DAY_MS) return null
        val release = source.latest()
        settings.update { it.copy(lastUpdateCheck = clock.millis()) }
        return release?.takeIf { isNewer(it.version, currentVersion) }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
