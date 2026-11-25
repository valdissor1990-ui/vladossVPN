package com.vpnproxy.ui.durability

import android.content.Context
import androidx.lifecycle.ViewModel
import com.vpnproxy.core.durability.BypassProfile
import com.vpnproxy.core.singbox.DurabilityOptions
import com.vpnproxy.core.singbox.DurabilitySettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class DurabilityUiState(
    val options: DurabilityOptions = DurabilityOptions(),
    val profile: BypassProfile = BypassProfile.NORMAL,
    /** True after the user changed a preset in this session. */
    val justSaved: Boolean = false
)

@HiltViewModel
class DurabilityViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(
        DurabilitySettings.load(context).let { options ->
            DurabilityUiState(options = options, profile = BypassProfile.detect(options))
        }
    )
    val state: StateFlow<DurabilityUiState> = _state.asStateFlow()

    fun selectProfile(profile: BypassProfile) {
        val next = profile.applyTo(_state.value.options)
        DurabilitySettings.save(context, next)
        _state.update {
            it.copy(options = next, profile = profile, justSaved = true)
        }
    }

    fun resetToNormal() = selectProfile(BypassProfile.NORMAL)
}
