package com.vpnproxy.core.durability

import com.vpnproxy.core.singbox.DurabilityOptions

/**
 * User-facing durability presets.
 *
 * A preset only changes transport hardening flags; it never touches the server, DNS or
 * routing. [NORMAL] is byte-for-byte the golden path, so choosing it is always a safe reset.
 *
 * Values are persisted through [com.vpnproxy.core.singbox.DurabilitySettings] and take effect
 * on the next core start, because the service reads them once at launch.
 */
enum class BypassProfile(
    val title: String,
    val summary: String
) {
    NORMAL(
        title = "Обычный",
        summary = "Без дополнительных изменений. Подходит для большинства сетей."
    ),

    HARD_NETWORK(
        title = "Сложная сеть",
        summary = "Дробит TLS-записи. Помогает, когда соединение рвётся на рукопожатии."
    ),

    MAXIMUM_STEALTH(
        title = "Максимальная маскировка",
        summary = "Дробит TLS и подменяет отпечаток браузера. Медленнее, включайте при упорной блокировке."
    );

    /** Applies this preset on top of [base], keeping unrelated options such as urltest. */
    fun applyTo(base: DurabilityOptions): DurabilityOptions = when (this) {
        NORMAL -> base.copy(
            tlsFragmentEnabled = false,
            tlsRecordFragmentEnabled = false,
            utlsFingerprint = null
        )
        HARD_NETWORK -> base.copy(
            tlsFragmentEnabled = true,
            tlsRecordFragmentEnabled = false,
            utlsFingerprint = null
        )
        MAXIMUM_STEALTH -> base.copy(
            tlsFragmentEnabled = true,
            tlsRecordFragmentEnabled = true,
            utlsFingerprint = "chrome"
        )
    }

    companion object {
        /** Best-effort match of stored options to a preset. */
        fun detect(options: DurabilityOptions): BypassProfile = when {
            options.tlsFragmentEnabled && options.tlsRecordFragmentEnabled -> MAXIMUM_STEALTH
            options.tlsFragmentEnabled || options.tlsRecordFragmentEnabled -> HARD_NETWORK
            else -> NORMAL
        }
    }
}
