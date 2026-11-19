package com.vpnproxy.ui.home

/**
 * Состояние VPN-подключения для UI главного экрана.
 */
enum class ConnectionUiState {
    DISCONNECTED,
    CONNECTING,
    VERIFYING,
    CONNECTED,
    UNSTABLE,
    ERROR;

    fun label(): String = when (this) {
        DISCONNECTED -> "ОТКЛЮЧЕНО"
        CONNECTING -> "ПОДКЛЮЧЕНИЕ"
        VERIFYING -> "ПРОВЕРКА"
        CONNECTED -> "ПОДКЛЮЧЕН"
        UNSTABLE -> "НЕСТАБИЛЬНО"
        ERROR -> "ОШИБКА"
    }

    fun hasTunnel(): Boolean = this == CONNECTED || this == UNSTABLE
}

/**
 * Цвет главной кнопки: зелёный — хорошо, жёлтый — средне,
 * красный — плохо, чёрный — связи нет или она постоянно рвётся.
 */
enum class LinkQuality {
    GOOD,
    MEDIUM,
    BAD,
    DEAD
}

fun linkQuality(
    state: ConnectionUiState,
    rttMs: Long,
    flapping: Boolean,
    successRate: Float = 1f
): LinkQuality {
    if (flapping || state == ConnectionUiState.DISCONNECTED || state == ConnectionUiState.ERROR) {
        return LinkQuality.DEAD
    }
    if (!state.hasTunnel()) return LinkQuality.DEAD
    if (state == ConnectionUiState.UNSTABLE) return LinkQuality.BAD
    if (successRate in 0f..0.45f) return LinkQuality.BAD
    if (rttMs <= 0L || rttMs == Long.MAX_VALUE) return LinkQuality.MEDIUM
    return when {
        rttMs < 300L && successRate >= 0.75f -> LinkQuality.GOOD
        rttMs < 800L && successRate >= 0.55f -> LinkQuality.MEDIUM
        else -> LinkQuality.BAD
    }
}
