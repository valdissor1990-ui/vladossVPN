package com.vpnproxy.core.diagnostics

import android.content.Context
import android.provider.Settings
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stable Remnawave-compatible HWID.
 *
 * Remnawave Panel 3.x validates x-hwid against ^[a-zA-Z0-9=-]{10,64}$.
 * We send a per-app SHA-256-derived identifier in the documented Android form:
 * "and-" + 32 lowercase hex characters. The raw Android ID is never transmitted.
 */
@Singleton
class DeviceIdManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS = "device_id_prefs"
        private const val KEY_HWID = "hwid"
        // App-specific salt prevents this app from exposing a cross-app identifier.
        private const val APP_SALT = "vladossvpn.remnawave.hwid.v1"
        private val HWID_PATTERN = Regex("^and-[0-9a-f]{32}$")
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

    /** Returns a stable, validated HWID for every subscription request. */
    @Synchronized
    fun getHwid(): String {
        val existing = prefs.getString(KEY_HWID, null)
        if (!existing.isNullOrBlank() && HWID_PATTERN.matches(existing)) return existing

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )?.takeIf { it.isNotBlank() }
            ?: error("Android ID unavailable; refusing to send an invalid subscription HWID")

        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$androidId:$APP_SALT".toByteArray(Charsets.UTF_8))
        val hex = digest.take(16).joinToString("") { byte -> "%02x".format(byte) }
        val created = "and-$hex"

        check(HWID_PATTERN.matches(created)) { "Generated subscription HWID has invalid format" }
        check(prefs.edit().putString(KEY_HWID, created).commit()) {
            "Unable to persist HWID in EncryptedSharedPreferences"
        }
        return created
    }
}
