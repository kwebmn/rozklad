package io.github.kwebmn.rozklad.ui.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kwebmn.rozklad.data.ScheduleRepository
import io.github.kwebmn.rozklad.domain.Subscription
import io.github.kwebmn.rozklad.domain.SubscriptionType
import io.github.kwebmn.rozklad.ui.errorMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

val FACULTIES = listOf(
    0 to "Усі факультети",
    1001 to "Факультет суспільних і прикладних наук",
    1002 to "Фаховий коледж",
    1004 to "Факультет медицини",
)

val SubscriptionType.minChars: Int
    get() = if (this == SubscriptionType.GROUP) 2 else 3

data class AddUiState(
    val type: SubscriptionType = SubscriptionType.GROUP,
    val faculty: Int = 0,
    val course: Int = 0,
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val searched: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val added: Subscription? = null,
)

class AddViewModel(private val repo: ScheduleRepository) : ViewModel() {
    private val _state = MutableStateFlow(AddUiState())
    val state: StateFlow<AddUiState> = _state.asStateFlow()
    private var searchJob: Job? = null

    fun setType(type: SubscriptionType) {
        _state.update { it.copy(type = type, suggestions = emptyList(), searched = false, error = null) }
        search()
    }

    fun setFaculty(faculty: Int) {
        _state.update { it.copy(faculty = faculty) }
        search()
    }

    fun setCourse(course: Int) {
        _state.update { it.copy(course = course) }
        search()
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query, error = null) }
        search()
    }

    private fun search() {
        searchJob?.cancel()
        val s = _state.value
        val query = s.query.trim()
        if (query.length < s.type.minChars) {
            _state.update { it.copy(suggestions = emptyList(), searched = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            try {
                val result = repo.suggest(s.type, query, s.faculty, s.course)
                _state.update { it.copy(suggestions = result, searched = true) }
            } catch (e: Exception) {
                val message = errorMessage(e) ?: throw e
                _state.update { it.copy(error = message) }
            }
        }
    }

    fun select(name: String) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val sub = repo.addSubscription(_state.value.type, name)
                _state.update { it.copy(loading = false, added = sub) }
            } catch (e: Exception) {
                val message = errorMessage(e) ?: throw e
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }

    fun consumeAdded() {
        searchJob?.cancel()
        _state.value = AddUiState()
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
    }
}
