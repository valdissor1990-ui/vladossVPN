package com.vpnproxy.core.diagnostics

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stable hardware-independent device id for subscription panel headers (Remnawave/Marzban).
 * Generated once and stored in EncryptedSharedPreferences.
 */
@Singleton
class DeviceIdManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS = "device_id_prefs"
        private const val KEY_HWID = "hwid"
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Returns a stable UUID string for this install. */
    @Synchronized
    fun getHwid(): String {
        val existing = prefs.getString(KEY_HWID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        check(prefs.edit().putString(KEY_HWID, created).commit()) {
            "Unable to persist HWID in EncryptedSharedPreferences"
        }
        return created
    }
}
