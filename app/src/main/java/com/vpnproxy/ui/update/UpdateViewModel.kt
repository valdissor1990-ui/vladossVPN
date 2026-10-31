package com.vpnproxy.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.update.UpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateManager: UpdateManager
) : ViewModel() {

    val state: StateFlow<UpdateManager.State> = updateManager.state

    private val _isCheckingUpdates = MutableStateFlow(false)
    /** true while a manual / forced check is in progress. */
    val isCheckingUpdates: StateFlow<Boolean> = _isCheckingUpdates.asStateFlow()

    sealed class Feedback {
        data object UpToDate : Feedback()
        data class Error(val message: String) : Feedback()
    }

    private val _feedback = MutableSharedFlow<Feedback>(extraBufferCapacity = 1)
    val feedback: SharedFlow<Feedback> = _feedback.asSharedFlow()

    private var checkedThisSession = false

    fun checkOnce() {
        if (checkedThisSession) return
        checkedThisSession = true
        // UpdateManager restores any pending APK once during singleton initialization.
        check(force = false)
    }

    fun check(force: Boolean = false) = viewModelScope.launch {
        if (_isCheckingUpdates.value) return@launch
        _isCheckingUpdates.value = true
        try {
            // Manual and automatic checks only announce updates. Download starts exclusively
            // from download(), which is called by the explicit "Скачать" button.
            updateManager.checkForUpdate(force = force)
            when (val s = updateManager.state.value) {
                is UpdateManager.State.UpToDate ->
                    _feedback.emit(Feedback.UpToDate)
                is UpdateManager.State.Error ->
                    _feedback.emit(
                        Feedback.Error(
                            if (looksLikeNetworkError(s.message)) {
                                "Не удалось проверить обновления. Проверьте интернет."
                            } else {
                                s.message
                            }
                        )
                    )
                // UpdateAvailable / Downloading / Ready → UpdateDialog
                else -> Unit
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            _feedback.emit(
                Feedback.Error("Не удалось проверить обновления. Проверьте интернет.")
            )
        } finally {
            _isCheckingUpdates.value = false
        }
    }

    fun download() {
        val current = updateManager.state.value
        if (current is UpdateManager.State.UpdateAvailable) {
            viewModelScope.launch { updateManager.downloadUpdate(current.release) }
        }
    }

    fun install() = updateManager.installReady()
    fun dismiss() = updateManager.reset()

    private fun looksLikeNetworkError(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("connect") ||
            m.contains("timeout") ||
            m.contains("unable to resolve") ||
            m.contains("unknownhost") ||
            m.contains("network") ||
            m.contains("unreachable") ||
            m.contains("ssl") ||
            m.contains("http")
    }
}
