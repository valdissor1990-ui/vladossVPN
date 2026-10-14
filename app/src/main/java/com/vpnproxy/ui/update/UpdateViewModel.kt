package com.vpnproxy.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.update.UpdateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateManager: UpdateManager
) : ViewModel() {

    val state: StateFlow<UpdateManager.State> = updateManager.state

    fun check() = viewModelScope.launch { updateManager.checkForUpdate() }

    fun download() {
        val current = updateManager.state.value
        if (current is UpdateManager.State.UpdateAvailable) {
            viewModelScope.launch { updateManager.downloadUpdate(current.release) }
        }
    }

    fun install() = updateManager.installReady()
    fun dismiss() = updateManager.reset()
}
