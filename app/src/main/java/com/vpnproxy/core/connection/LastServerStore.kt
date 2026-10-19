package com.vpnproxy.core.connection

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists last successfully selected VPN server identity for BootReceiver auto-connect.
 */
@Singleton
class LastServerStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class SavedServer(
        val address: String,
        val port: Int,
        val name: String,
        val uuid: String
    )

    fun save(address: String, port: Int, name: String, uuid: String) {
        prefs.edit()
            .putString(KEY_ADDRESS, address)
            .putInt(KEY_PORT, port)
            .putString(KEY_NAME, name)
            .putString(KEY_UUID, uuid)
            .apply()
    }

    fun load(): SavedServer? {
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        val port = prefs.getInt(KEY_PORT, -1)
        if (port <= 0) return null
        val name = prefs.getString(KEY_NAME, address) ?: address
        val uuid = prefs.getString(KEY_UUID, "") ?: ""
        return SavedServer(address, port, name, uuid)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS = "vpnproxy_last_server"
        private const val KEY_ADDRESS = "address"
        private const val KEY_PORT = "port"
        private const val KEY_NAME = "name"
        private const val KEY_UUID = "uuid"
    }
}
