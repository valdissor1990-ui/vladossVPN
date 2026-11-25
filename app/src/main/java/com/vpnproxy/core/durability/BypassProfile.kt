package com.vpnproxy.core.durability

import com.vpnproxy.core.singbox.DurabilityOptions

/**
 * User-facing durability presets.
 *
 * A preset only changes transport hardening flags; it never touches the server, DNS or
 * routing. [NORMAL] clears exactly those transport flags, so choosing it is always a safe
 * reset, but unrelated options such as urltest are preserved.
 *
 * Values are persisted through [com.vpnproxy.core.singbox.DurabilitySettings] and take effect
 * on the next core start, because the service reads them once at launch.
 */
enum class BypassProfile(
    val title: String,
    val summary: String,
    /** False for states that are detected but never offered as a selectable preset. */
    val selectable: Boolean = true
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
    ),

    CUSTOM(
        title = "Настроено вручную",
        summary = "Параметры маскировки отличаются от готовых профилей. Выберите профиль, чтобы заменить их.",
        selectable = false
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
            utlsFingerprint = STEALTH_FINGERPRINT
        )
        // Detection-only state: never rewrite options that the user configured elsewhere.
        CUSTOM -> base
    }

    companion object {
        const val STEALTH_FINGERPRINT = "chrome"

        /**
         * Exact match against the three presets. Anything else, including a lone uTLS
         * fingerprint or a non-default fingerprint value, is [CUSTOM] so the UI never claims
         * that an unrelated configuration is a known preset.
         */
        fun detect(options: DurabilityOptions): BypassProfile = when {
            !options.tlsFragmentEnabled &&
                !options.tlsRecordFragmentEnabled &&
                options.utlsFingerprint == null -> NORMAL

            options.tlsFragmentEnabled &&
                !options.tlsRecordFragmentEnabled &&
                options.utlsFingerprint == null -> HARD_NETWORK

            options.tlsFragmentEnabled &&
                options.tlsRecordFragmentEnabled &&
                options.utlsFingerprint == STEALTH_FINGERPRINT -> MAXIMUM_STEALTH

            else -> CUSTOM
        }
    }
}
