package com.vpnproxy.core.connection

import android.content.Context
import android.os.Build
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists last successfully selected VPN server for BootReceiver auto-connect.
 * Uses device-protected preferences for the auto-start identity.
 */
@Singleton
class LastServerStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = run {
        val storageContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext() ?: context
        } else {
            context
        }
        storageContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    data class SavedServer(
        val address: String,
        val port: Int,
        val name: String,
        val uuid: String,
        val fingerprint: String = ""
    )

    fun save(
        address: String,
        port: Int,
        name: String,
        uuid: String,
        connectionKey: String = ""
    ) {
        val editor = prefs.edit()
        if (connectionKey.isNotBlank()) {
            // Persist only a one-way profile fingerprint, not endpoint or credentials.
            editor.remove(KEY_ADDRESS)
                .remove(KEY_PORT)
                .remove(KEY_NAME)
                .remove(KEY_UUID)
                .putString(KEY_FINGERPRINT, fingerprint(connectionKey))
        } else {
            // Compatibility for legacy call sites until they pass a stable connection key.
            editor.putString(KEY_ADDRESS, address)
                .putInt(KEY_PORT, port)
                .putString(KEY_NAME, name)
                .putString(KEY_UUID, uuid)
                .remove(KEY_FINGERPRINT)
        }
        editor.apply()
    }

    fun fingerprint(connectionKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(connectionKey.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    fun load(): SavedServer? {
        val fingerprint = prefs.getString(KEY_FINGERPRINT, "") ?: ""
        if (fingerprint.isNotBlank()) return SavedServer("", -1, "", "", fingerprint)

        // Read legacy endpoint/UUID records written by previous app versions.
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        val port = prefs.getInt(KEY_PORT, -1)
        if (port <= 0) return null
        val name = prefs.getString(KEY_NAME, address) ?: address
        val uuid = prefs.getString(KEY_UUID, "") ?: ""
        return SavedServer(address, port, name, uuid, "")
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
        private const val KEY_FINGERPRINT = "fingerprint"
    }
}
