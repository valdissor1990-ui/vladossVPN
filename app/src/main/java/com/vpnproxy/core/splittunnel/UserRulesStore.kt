package com.vpnproxy.core.splittunnel

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.splitTunnelDataStore by preferencesDataStore(name = "vpnproxy_split_tunnel")

enum class SplitTunnelMode {
    ALL_APPS,
    EXCLUDE_SELECTED,
    INCLUDE_SELECTED
}

data class SplitTunnelRules(
    val mode: SplitTunnelMode = SplitTunnelMode.ALL_APPS,
    val selectedPackages: Set<String> = emptySet()
)

@Singleton
class UserRulesStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        val MODE = stringPreferencesKey("mode")
        val PACKAGES = stringSetPreferencesKey("selected_packages")
    }

    val rules = context.splitTunnelDataStore.data.map { preferences ->
        SplitTunnelRules(
            mode = preferences[MODE]?.let { value ->
                SplitTunnelMode.entries.firstOrNull { it.name == value }
            } ?: SplitTunnelMode.ALL_APPS,
            selectedPackages = preferences[PACKAGES] ?: emptySet()
        )
    }

    suspend fun readRules(): SplitTunnelRules = rules.first()

    suspend fun saveRules(rules: SplitTunnelRules) {
        val safeRules = rules.copy(selectedPackages = rules.selectedPackages - context.packageName)
        context.splitTunnelDataStore.edit { preferences ->
            preferences[MODE] = safeRules.mode.name
            preferences[PACKAGES] = safeRules.selectedPackages
        }
    }

    suspend fun updateRules(transform: (SplitTunnelRules) -> SplitTunnelRules) {
        context.splitTunnelDataStore.edit { preferences ->
            val current = SplitTunnelRules(
                mode = preferences[MODE]?.let { value ->
                    SplitTunnelMode.entries.firstOrNull { it.name == value }
                } ?: SplitTunnelMode.ALL_APPS,
                selectedPackages = preferences[PACKAGES] ?: emptySet()
            )
            val transformed = transform(current)
            val updated = transformed.copy(
                selectedPackages = transformed.selectedPackages - context.packageName
            )
            preferences[MODE] = updated.mode.name
            preferences[PACKAGES] = updated.selectedPackages
        }
    }
}
