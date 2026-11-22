package com.vpnproxy.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.core.dualpath.DualPathStore
import com.vpnproxy.core.singbox.RoutingPreset
import com.vpnproxy.core.singbox.RoutingSettings
import com.vpnproxy.core.singbox.RuleSetStore
import com.vpnproxy.update.UpdateCheckWorker
import com.vpnproxy.smartconnect.BatteryPolicy
import com.vpnproxy.subscription.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private val Context.settingsDataStore by preferencesDataStore(name = "vpnproxy_settings")

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dualPathStore: DualPathStore,
    private val subscriptionRepository: SubscriptionRepository
) : ViewModel() {

    companion object {
        val KEY_AUTO_START = booleanPreferencesKey("auto_start")
        val KEY_CHECK_UPDATES = booleanPreferencesKey("check_updates")
    }

    val autoStart: StateFlow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_AUTO_START] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val checkUpdates: StateFlow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_CHECK_UPDATES] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val deviceMetadataConsent: StateFlow<Boolean> = subscriptionRepository.deviceMetadataConsent
    private val deviceMetadataConsentMutex = Mutex()

    fun setDeviceMetadataConsent(enabled: Boolean) {
        viewModelScope.launch {
            deviceMetadataConsentMutex.withLock {
                withContext(Dispatchers.IO) {
                    subscriptionRepository.setDeviceMetadataConsent(enabled)
                }
            }
        }
    }

    private val _batteryEconomyMode = MutableStateFlow(BatteryPolicy.isEconomyModeEnabled(context))
    val batteryEconomyMode: StateFlow<Boolean> = _batteryEconomyMode.asStateFlow()

    val dualPathEnabled: StateFlow<Boolean> = dualPathStore.settings
        .map { it.enabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val dualPathSelectedCount: StateFlow<Int> = dualPathStore.settings
        .map { it.packages.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun setBatteryEconomyMode(enabled: Boolean) {
        BatteryPolicy.setEconomyModeEnabled(context, enabled)
        _batteryEconomyMode.value = enabled
    }

    fun setDualPathEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dualPathStore.setEnabled(enabled)
        }
    }

    sealed interface RuleSetState {
        data object Idle : RuleSetState
        data object Downloading : RuleSetState
        data object Ready : RuleSetState
        data class Failed(val message: String) : RuleSetState
    }

    private val _routingPreset = MutableStateFlow(RoutingSettings.preset(context))
    val routingPreset: StateFlow<RoutingPreset> = _routingPreset.asStateFlow()

    private val _ruleSetState = MutableStateFlow<RuleSetState>(
        if (RuleSetStore.isReady(context)) RuleSetState.Ready else RuleSetState.Idle
    )
    val ruleSetState: StateFlow<RuleSetState> = _ruleSetState.asStateFlow()

    /**
     * Persists the choice and, for [RoutingPreset.BYPASS_RU], fetches the local rule-sets.
     * A failed download keeps the previous preset on disk from the core's point of view only
     * after the next connect; the builder degrades to bypass_lan when files are missing.
     */
    fun setRoutingPreset(preset: RoutingPreset) {
        RoutingSettings.setPreset(context, preset)
        _routingPreset.value = preset
        if (preset != RoutingPreset.BYPASS_RU) {
            _ruleSetState.value = if (RuleSetStore.isReady(context)) RuleSetState.Ready else RuleSetState.Idle
            return
        }
        refreshRuleSets(force = false)
    }

    fun refreshRuleSets(force: Boolean = true) {
        viewModelScope.launch {
            _ruleSetState.value = RuleSetState.Downloading
            _ruleSetState.value = RuleSetStore.ensure(context, force = force).fold(
                onSuccess = { RuleSetState.Ready },
                onFailure = { RuleSetState.Failed(it.message ?: "не удалось загрузить правила") }
            )
        }
    }

    fun setAutoStart(enabled: Boolean) {        val bootPrefsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext() ?: context
        } else {
            context
        }
        bootPrefsContext.getSharedPreferences("vladoss_ui_preferences", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("auto_start", enabled)
            .apply()

        viewModelScope.launch {
            context.settingsDataStore.edit { it[KEY_AUTO_START] = enabled }
        }
    }

    fun setCheckUpdates(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[KEY_CHECK_UPDATES] = enabled }
            if (enabled) {
                UpdateCheckWorker.schedule(context, intervalHours = 6)
            } else {
                androidx.work.WorkManager.getInstance(context)
                    .cancelUniqueWork(UpdateCheckWorker.WORK_NAME)
            }
        }
    }
}
