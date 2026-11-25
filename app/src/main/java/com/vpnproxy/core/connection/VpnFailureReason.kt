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
    /** Local proxy port collision; retry the same selected server after lifecycle cooldown. */
    data object BindError : VpnFailureReason()
    data object Unknown : VpnFailureReason()

    fun isRetryable(): Boolean = when (this) {
        is AnotherVpnActive,
        is PermissionDenied,
        is TunConflict -> false
        is NetworkTimeout,
        is BindError -> true
        is CoreError -> !isFatalCoreMessage(message)
        is Unknown -> true
    }

    /**
     * Whether changing transport hardening (TLS fragmentation, uTLS fingerprint) can
     * plausibly help. A local port collision or a broken profile is retryable by the
     * coordinator but must not consume an autopilot preset change.
     */
    fun isPresetRetryable(): Boolean = when (this) {
        is NetworkTimeout -> true
        // A local core start failure is retried and may fall back to another server, but a
        // TLS preset cannot fix it. Keep this check here instead of isFatalCoreMessage():
        // that predicate also disables coordinator retries and server fallback.
        is CoreError -> !isFatalCoreMessage(message) && !isLocalCoreStartFailure(message)
        // A blank or unknown failure carries no evidence that TLS hardening can help.
        else -> false
    }

    /**
     * A malformed profile should be skipped when other profiles remain. Keep its
     * ordinary retry classification fatal so we do not retry the same broken profile
     * after the candidate list has been exhausted.
     */
    fun canFallbackToNextServer(): Boolean = when (this) {
        is BindError -> false
        is CoreError -> isProfileConfigurationError(message) || isRetryable()
        else -> isRetryable()
    }

    fun userMessage(): String = when (this) {
        is AnotherVpnActive -> "Другое VPN уже активно. Отключите его."
        is PermissionDenied -> "Нет разрешения VPN. Выдайте доступ в настройках."
        is TunConflict -> "Не удалось создать TUN. Закройте другое VPN и повторите."
        is NetworkTimeout -> if (details.isNotBlank()) "Превышено время подключения: $details" else "Превышено время подключения"
        is CoreError -> if (isConfigurationError(message)) {
            "Ошибка конфигурации сервера. Попробуйте обновить подписку."
        } else {
            message.ifBlank { "Ошибка ядра VPN" }
        }
        is BindError -> "Локальный порт VPN занят. Повтор подключения через 5 секунд."
        is Unknown -> "Неизвестная ошибка подключения"
    }

    companion object {
        private fun isConfigurationError(message: String): Boolean {
            val m = message.lowercase()
            return m.contains("unknown field") ||
                m.contains("decode config") ||
                m.contains("invalid configuration") ||
                m.contains("invalid config") ||
                m.contains("failed to parse config") ||
                m.contains("configuration schema") ||
                (m.contains("illegalstateexception") && m.contains("config") && m.contains("libbox"))
        }

        private fun isProfileConfigurationError(message: String): Boolean =
            isConfigurationError(message)

        /**
         * A local libbox start failure: the coordinator should retry and fall back to the next
         * server, but the bypass autopilot must not rewrite TLS settings for it.
         */
        private fun isLocalCoreStartFailure(message: String): Boolean {
            val m = message.lowercase()
            return m.contains("unable to start libbox") || m.contains("failed to start libbox")
        }

        private fun isFatalCoreMessage(message: String): Boolean {
            val m = message.lowercase()
            return isConfigurationError(message) ||
                m.contains("another vpn") ||
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
                m.contains("address already in use") || m.contains("bind:") || m.contains("eaddrinuse") -> BindError
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
