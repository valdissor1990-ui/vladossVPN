package com.vpnproxy.core.connection

/**
 * Typed VPN failure reasons. Fatal reasons never auto-retry and lock the actor
 * until the user explicitly presses Connect again.
 */
sealed class VpnFailureReason {
    data object AnotherVpnActive : VpnFailureReason()
    data object PermissionDenied : VpnFailureReason()
    data object TunConflict : VpnFailureReason()
    data class NetworkTimeout(val details: String = "") : VpnFailureReason()
    data class CoreError(val message: String) : VpnFailureReason()
    data object Unknown : VpnFailureReason()

    fun isRetryable(): Boolean = when (this) {
        is AnotherVpnActive,
        is PermissionDenied,
        is TunConflict -> false
        is NetworkTimeout -> true
        is CoreError -> !isFatalCoreMessage(message)
        is Unknown -> true
    }

    fun userMessage(): String = when (this) {
        is AnotherVpnActive -> "Другое VPN уже активно. Отключите его."
        is PermissionDenied -> "Нет разрешения VPN. Выдайте доступ в настройках."
        is TunConflict -> "Не удалось создать TUN. Закройте другое VPN и повторите."
        is NetworkTimeout -> if (details.isNotBlank()) "Превышено время подключения: $details" else "Превышено время подключения"
        is CoreError -> message.ifBlank { "Ошибка ядра VPN" }
        is Unknown -> "Неизвестная ошибка подключения"
    }

    companion object {
        private fun isFatalCoreMessage(message: String): Boolean {
            val m = message.lowercase()
            return m.contains("another vpn") ||
                m.contains("permission denied") ||
                m.contains("permission is missing") ||
                m.contains("permission revoked") ||
                m.contains("establish failed") ||
                m.contains("tun conflict")
        }

        /** Best-effort parse of legacy string errors into typed reasons. */
        fun fromMessage(raw: String?): VpnFailureReason {
            if (raw.isNullOrBlank()) return Unknown
            val m = raw.lowercase()
            return when {
                m.contains("another vpn") -> AnotherVpnActive
                m.contains("permission is missing") || m.contains("permission denied") || m.contains("permission revoked") ->
                    PermissionDenied
                m.contains("establish failed") || m.contains("establish() returned null") || m.contains("tun") ->
                    TunConflict
                m.contains("timeout") || m.contains("превышено время") ->
                    NetworkTimeout(raw)
                else -> CoreError(raw)
            }
        }
    }
}

/** Thrown from platform / core start path with a typed reason. */
class VpnStartException(
    val reason: VpnFailureReason,
    cause: Throwable? = null
) : Exception(reason.userMessage(), cause)
