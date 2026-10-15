package io.github.kwebmn.rozklad.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kwebmn.rozklad.data.UpdateChecker
import io.github.kwebmn.rozklad.data.remote.NetworkException
import io.github.kwebmn.rozklad.data.remote.ReleaseInfo
import io.github.kwebmn.rozklad.update.ApkDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data object Downloading : UpdateState
    data object Ready : UpdateState
    data class Failed(val message: String) : UpdateState
}

class UpdateViewModel(
    private val checker: UpdateChecker,
    private val downloader: ApkDownloader,
) : ViewModel() {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun check(force: Boolean) {
        if (force) _state.value = UpdateState.Checking
        viewModelScope.launch {
            _state.value = try {
                val release = checker.check(force)
                when {
                    release != null -> UpdateState.Available(release)
                    force -> UpdateState.UpToDate
                    else -> UpdateState.Idle
                }
            } catch (e: NetworkException) {
                if (force) UpdateState.Failed("Не вдалося перевірити оновлення") else UpdateState.Idle
            }
        }
    }

    fun download() {
        val release = (_state.value as? UpdateState.Available)?.release ?: return
        _state.value = UpdateState.Downloading
        viewModelScope.launch {
            _state.value = if (downloader.download(release)) {
                UpdateState.Ready
            } else {
                UpdateState.Failed("Не вдалося завантажити оновлення")
            }
        }
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }
}
