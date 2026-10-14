package com.vpnproxy.ui.home

/**
 * Состояние VPN-подключения для UI главного экрана.
 */
enum class ConnectionUiState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    UNSTABLE,
    ERROR;

    fun label(): String = when (this) {
        DISCONNECTED -> "Отключено"
        CONNECTING -> "Подключение…"
        CONNECTED -> "Подключено"
        UNSTABLE -> "Нестабильно"
        ERROR -> "Ошибка"
    }
}
