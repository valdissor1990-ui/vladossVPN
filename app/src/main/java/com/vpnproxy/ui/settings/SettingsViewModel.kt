package com.vpnproxy.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.subscription.SubscriptionUpdateWorker
import com.vpnproxy.update.UpdateCheckWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private val Context.settingsDataStore by preferencesDataStore(name = "vpnproxy_settings")

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        val KEY_AUTO_START = booleanPreferencesKey("auto_start")
        val KEY_KILL_SWITCH = booleanPreferencesKey("kill_switch")
        val KEY_CHECK_UPDATES = booleanPreferencesKey("check_updates")
        val KEY_UPDATE_INTERVAL_HOURS = intPreferencesKey("update_interval_hours")
    }

    val autoStart: StateFlow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_AUTO_START] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val killSwitch: StateFlow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_KILL_SWITCH] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val checkUpdates: StateFlow<Boolean> = context.settingsDataStore.data
        .map { it[KEY_CHECK_UPDATES] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val updateInterval: StateFlow<Int> = context.settingsDataStore.data
        .map { it[KEY_UPDATE_INTERVAL_HOURS] ?: 6 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 6)

    fun setAutoStart(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[KEY_AUTO_START] = enabled }
        }
    }

    fun setKillSwitch(enabled: Boolean) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[KEY_KILL_SWITCH] = enabled }
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

    fun setUpdateInterval(hours: Int) {
        viewModelScope.launch {
            context.settingsDataStore.edit { it[KEY_UPDATE_INTERVAL_HOURS] = hours }
            SubscriptionUpdateWorker.schedule(context, hours)
        }
    }
}
