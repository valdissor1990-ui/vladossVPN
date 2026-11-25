package com.vpnproxy.ui.durability

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.core.durability.BypassProfile
import com.vpnproxy.core.singbox.DurabilityOptions
import com.vpnproxy.core.singbox.DurabilitySettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DurabilityUiState(
    val options: DurabilityOptions = DurabilityOptions(),
    val profile: BypassProfile = BypassProfile.NORMAL,
    /** True briefly after the user changed a preset in this session. */
    val justSaved: Boolean = false
)

@HiltViewModel
class DurabilityViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(DurabilitySettings.PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        DurabilitySettings.load(context).let { options ->
            DurabilityUiState(options = options, profile = BypassProfile.detect(options))
        }
    )
    val state: StateFlow<DurabilityUiState> = _state.asStateFlow()

    private var savedNoticeJob: Job? = null

    /**
     * The autopilot or the debug screen may change the same preferences while this screen is
     * open. Reload on every write so the selected card never lies about the applied preset.
     */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val options = DurabilitySettings.load(context)
        _state.update { it.copy(options = options, profile = BypassProfile.detect(options)) }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    fun selectProfile(profile: BypassProfile) {
        if (!profile.selectable) return
        val current = _state.value
        if (current.profile == profile) {
            savedNoticeJob?.cancel()
            _state.update { it.copy(justSaved = false) }
            return
        }
        val next = profile.applyTo(current.options)
        DurabilitySettings.save(context, next)
        _state.update { it.copy(options = next, profile = profile, justSaved = true) }
        savedNoticeJob?.cancel()
        savedNoticeJob = viewModelScope.launch {
            delay(SAVED_NOTICE_MS)
            _state.update { it.copy(justSaved = false) }
        }
    }

    fun resetToNormal() = selectProfile(BypassProfile.NORMAL)

    override fun onCleared() {
        savedNoticeJob?.cancel()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onCleared()
    }

    companion object {
        private const val SAVED_NOTICE_MS = 4_000L
    }
}
