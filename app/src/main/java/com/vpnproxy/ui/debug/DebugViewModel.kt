package com.vpnproxy.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.logging.LogEntry
import com.vpnproxy.logging.LogExporter
import com.vpnproxy.logging.LogLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logCollector: LogCollector,
    private val logExporter: LogExporter
) : ViewModel() {

    val filteredLogs: StateFlow<List<LogEntry>> = logCollector.filteredLogs

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var currentSearch: String? = null
    private var currentLevel: LogLevel? = null

    fun setSearch(query: String) {
        currentSearch = query.ifBlank { null }
        applyFilters()
    }

    fun setLevelFilter(level: LogLevel?) {
        currentLevel = level
        applyFilters()
    }

    private fun applyFilters() {
        logCollector.setFilter(currentLevel, null, currentSearch)
    }

    fun copyAll() {
        try {
            val text = logExporter.buildExportText()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("VPNProxy Logs", text))
            _message.value = "Скопировано: ${text.length} символов"
        } catch (t: Throwable) {
            _message.value = "Ошибка копирования: ${t.message}"
        }
    }

    fun export() {
        viewModelScope.launch {
            val uri = logExporter.exportToDownloads()
            _message.value = if (uri != null) {
                "Сохранено в Downloads/"
            } else {
                "Не удалось сохранить журнал"
            }
        }
    }

    fun clear() { logCollector.clear() }
    fun clearMessage() { _message.value = null }
}
