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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel экрана отладки.
 * Управляет фильтрами, копированием, экспортом логов.
 */
@HiltViewModel
class DebugViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logCollector: LogCollector,
    private val logExporter: LogExporter
) : ViewModel() {

    val filteredLogs: StateFlow<List<LogEntry>> = logCollector.filteredLogs

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

    /** Копирует все логи в буфер обмена (с маскировкой чувствительных данных). */
    fun copyAll() {
        try {
            val text = logExporter.buildExportText()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("VPNProxy Logs", text))
        } catch (_: Exception) { /* ignore */ }
    }

    /** Экспортирует логи в Downloads/ через MediaStore. */
    fun export() {
        viewModelScope.launch {
            logExporter.exportToDownloads()
        }
    }

    fun clear() {
        logCollector.clear()
    }
}
