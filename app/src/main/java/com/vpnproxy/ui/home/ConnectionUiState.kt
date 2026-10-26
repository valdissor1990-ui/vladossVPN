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
        DISCONNECTED -> "Отключено"
        CONNECTING -> "Подключение…"
        VERIFYING -> "Проверка связи…"
        CONNECTED -> "Подключено"
        UNSTABLE -> "Нестабильно"
        ERROR -> "Ошибка"
    }
}
