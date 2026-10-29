package com.vpnproxy.core.dualpath

import android.content.Context
import android.content.pm.PackageManager
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dualPathDataStore by preferencesDataStore(name = "vpnproxy_dual_path")

data class DualPathSettings(
    /** Master switch: when false, no process_name foreign routing is applied. */
    val enabled: Boolean = true,
    /** User-selected package names routed to the foreign outbound. Empty by default. */
    val packages: Set<String> = emptySet()
)

@Singleton
class DualPathStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        val KEY_ENABLED = booleanPreferencesKey("enabled")
        val KEY_PACKAGES = stringSetPreferencesKey("packages")
    }

    val settings: Flow<DualPathSettings> = context.dualPathDataStore.data.map { prefs ->
        DualPathSettings(
            enabled = prefs[KEY_ENABLED] ?: true,
            packages = prefs[KEY_PACKAGES] ?: emptySet()
        )
    }

    suspend fun read(): DualPathSettings = settings.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.dualPathDataStore.edit { it[KEY_ENABLED] = enabled }
    }

    suspend fun setPackages(packages: Set<String>) {
        context.dualPathDataStore.edit {
            it[KEY_PACKAGES] = packages - context.packageName
        }
    }

    suspend fun setPackageSelected(packageName: String, selected: Boolean) {
        if (packageName == context.packageName) return
        context.dualPathDataStore.edit { prefs ->
            val current = prefs[KEY_PACKAGES] ?: emptySet()
            prefs[KEY_PACKAGES] = if (selected) current + packageName else current - packageName
        }
    }

    /** Installed packages from the current dual-path selection (for sing-box rules). */
    suspend fun installedPackagesForRules(): List<String> {
        val s = read()
        if (!s.enabled || s.packages.isEmpty()) return emptyList()
        val pm = context.packageManager
        return s.packages.filter { pkg ->
            try {
                pm.getApplicationInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }.sorted()
    }
}
