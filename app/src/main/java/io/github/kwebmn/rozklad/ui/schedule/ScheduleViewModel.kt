package io.github.kwebmn.rozklad.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.domain.DateRange
import io.github.kwebmn.rozklad.domain.Lesson
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.chooseStartDay
import io.github.kwebmn.rozklad.domain.syncWindow
import io.github.kwebmn.rozklad.domain.weekStart
import io.github.kwebmn.rozklad.ui.NETWORK_ERROR
import io.github.kwebmn.rozklad.ui.errorMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

data class ScheduleUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val current: Subscription? = null,
    val weekStart: LocalDate,
    val selectedDate: LocalDate,
    val weekLessons: List<Lesson> = emptyList(),
    val refreshing: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
) {
    val dayLessons: List<Lesson> get() = weekLessons.filter { it.date == selectedDate }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleViewModel(
    private val repo: ScheduleRepository,
    private val clock: Clock,
    private val onSynced: () -> Unit = {},
) : ViewModel() {
    private data class Status(val refreshing: Boolean = false, val offline: Boolean = false, val error: String? = null)

    private val today: LocalDate get() = LocalDate.now(clock)
    private val selectedSubId = MutableStateFlow<Long?>(null)
    private val selectedDate = MutableStateFlow(today)
    private val week = MutableStateFlow(weekStart(today))
    private val status = MutableStateFlow(Status())
    private val loadedWeeks = mutableSetOf<Pair<Long, LocalDate>>()

    private val current: StateFlow<Subscription?> =
        combine(repo.subscriptions, selectedSubId) { subs, id ->
            subs.firstOrNull { it.id == id } ?: subs.firstOrNull { it.isPrimary } ?: subs.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val weekLessons =
        combine(current.map { it?.id }.distinctUntilChanged(), week) { id, w -> id to w }
            .flatMapLatest { (id, w) ->
                if (id == null) flowOf(emptyList()) else repo.lessons(id, DateRange(w, w.plusDays(6)))
            }

    private val view = combine(week, selectedDate, weekLessons) { w, d, l -> Triple(w, d, l) }

    val state: StateFlow<ScheduleUiState> =
        combine(repo.subscriptions, current, view, status) { subs, cur, (w, d, lessons), st ->
            ScheduleUiState(subs, cur, w, d, lessons, st.refreshing, st.offline, st.error)
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ScheduleUiState(weekStart = week.value, selectedDate = selectedDate.value),
        )

    init {
        viewModelScope.launch {
            val sub = current.filterNotNull().first()
            val now = LocalDateTime.now(clock)
            showDate(chooseStartDay(now, repo.lessonsOnce(sub.id, syncWindow(now.toLocalDate()))))
            val last = sub.lastSyncedAt
            if (last == null || clock.millis() - last > STALE_MS) refresh()
        }
    }

    fun showDate(date: LocalDate) {
        selectedDate.value = date
        week.value = weekStart(date)
        loadIfOutsideWindow(date)
    }

    fun shiftDay(delta: Long) = showDate(selectedDate.value.plusDays(delta))

    fun shiftWeek(delta: Long) = showDate(selectedDate.value.plusWeeks(delta))

    fun selectSubscription(id: Long) {
        selectedSubId.value = id
        status.value = Status(refreshing = status.value.refreshing)
        viewModelScope.launch {
            val sub = current.first { it?.id == id } ?: return@launch
            val last = sub.lastSyncedAt
            if (last == null || clock.millis() - last > STALE_MS) refresh() else loadIfOutsideWindow(selectedDate.value)
        }
    }

    fun refresh() {
        val sub = current.value ?: return
        sync {
            repo.refresh(sub)
            val w = week.value
            if (w !in syncWindow(today)) repo.refresh(sub, DateRange(w, w.plusDays(6)))
            onSynced()
        }
    }

    private fun loadIfOutsideWindow(date: LocalDate) {
        val sub = current.value ?: return
        if (date in syncWindow(today)) return
        val start = weekStart(date)
        val key = sub.id to start
        if (!loadedWeeks.add(key)) return
        val started = sync {
            try {
                repo.refresh(sub, DateRange(start, start.plusDays(6)))
            } catch (e: Exception) {
                loadedWeeks.remove(key)
                throw e
            }
        }
        if (!started) loadedWeeks.remove(key)
    }

    /** Единственная точка сетевой работы: пропускается, пока идёт другая; итоговый статус пишется один раз. */
    private fun sync(block: suspend () -> Unit): Boolean {
        if (status.value.refreshing) return false
        status.value = status.value.copy(refreshing = true)
        viewModelScope.launch { status.value = runSync(block) }
        return true
    }

    private suspend fun runSync(block: suspend () -> Unit): Status = try {
        block()
        Status()
    } catch (e: Exception) {
        when (val message = errorMessage(e) ?: throw e) {
            NETWORK_ERROR -> Status(offline = true)
            else -> Status(error = message)
        }
    }

    private companion object {
        const val STALE_MS = 30 * 60 * 1000L
    }
}
