package com.vpnproxy.ui.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.core.scanner.ServerScanner
import com.vpnproxy.subscription.JsonConfigRepository
import com.vpnproxy.subscription.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val repository: SubscriptionRepository,
    private val jsonConfigRepository: JsonConfigRepository,
    private val serverScanner: ServerScanner,
    private val logCollector: LogCollector
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _usingCache = MutableStateFlow(false)
    val usingCache: StateFlow<Boolean> = _usingCache.asStateFlow()

    init {
        viewModelScope.launch {
            repository.lastRefreshUsedCache.collect { _usingCache.value = it }
        }
    }

    val subscriptions = repository.subscriptions
    val allConfigs = repository.allConfigs
    val jsonConfigs = jsonConfigRepository.configs
    val scannedServers = serverScanner.scannedServers
    val isScanning = serverScanner.isScanning

    fun addSubscription(url: String, name: String, tag: String = "") {
        if (url.isBlank()) {
            _message.value = "URL не может быть пустым"
            return
        }
        val trimmed = url.trim()
        // JSON config paste (sing-box outbound / array) — not a URL.
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            addFromJsonConfig(trimmed, name, tag)
            return
        }
        viewModelScope.launch {
            val displayName = name.ifBlank { "Provider ${subscriptions.value.size + 1}" }
            val ok = repository.addSubscription(trimmed, displayName, tag)
            _message.value = if (ok) "Подписка добавлена" else "Ошибка добавления"
            if (ok) refreshAll()
        }
    }

    fun addFromJsonConfig(rawJson: String, name: String, tag: String = "") {
        if (rawJson.isBlank()) {
            _message.value = "JSON не может быть пустым"
            return
        }
        viewModelScope.launch {
            val displayName = name.ifBlank { "JSON ${subscriptions.value.size + 1}" }
            val count = jsonConfigRepository.addFromJsonConfig(rawJson, displayName, tag)
            _message.value = when {
                count == null -> "Не удалось разобрать JSON"
                count == 0 -> "В JSON нет поддерживаемых серверов"
                else -> "Добавлено серверов из JSON: $count"
            }
            if (count != null && count > 0) {
                val merged = jsonConfigRepository.allConfigsIncluding(repository.getAllConfigs())
                runCatching {
                    serverScanner.scanAfterSubscriptionUpdate(merged)
                }
            }
        }
    }

    fun updateTag(id: String, tag: String) {
        repository.updateSubscriptionTag(id, tag)
        _message.value = "Тег обновлён"
    }

    fun setSubscriptionEnabled(id: String, enabled: Boolean) {
        if (id !in SubscriptionRepository.BUILT_IN_IDS) return
        repository.setSubscriptionEnabled(id, enabled)
        _message.value = if (enabled) "Источник включён" else "Источник отключён"
        refreshAll()
    }

    fun removeSubscription(id: String) {
        viewModelScope.launch {
            repository.removeSubscription(id)
            _message.value = if (id in SubscriptionRepository.BUILT_IN_IDS) {
                "Встроенные источники нельзя удалить — их можно отключить"
            } else {
                "Подписка удалена"
            }
        }
    }

    fun refreshAll() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                repository.refreshAll()
                val merged = jsonConfigRepository.allConfigsIncluding(repository.getAllConfigs())
                val scanResults = serverScanner.scanAfterSubscriptionUpdate(merged)
                val tcpResponsiveCount = scanResults.count { it.successRate > 0.5f }
                _message.value = "Профилей: ${scanResults.size}; TCP-порт ответил у $tcpResponsiveCount. Это не подтверждает работу VPN."
                logCollector.info(
                    "SubscriptionsVM",
                    "Refreshed and scanned: tcpResponsive=$tcpResponsiveCount; scannedUnique=${scanResults.size}"
                )
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _message.value = "Ошибка: ${e.message}"
                logCollector.error("SubscriptionsVM", "Refresh failed", e)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun clearMessage() { _message.value = null }
}
