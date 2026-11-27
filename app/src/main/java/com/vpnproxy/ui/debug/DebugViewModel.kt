package com.vpnproxy.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.core.diagnostics.BatteryLogger
import com.vpnproxy.core.singbox.DiagnosticOptions
import com.vpnproxy.core.singbox.DiagnosticSettings
import com.vpnproxy.core.singbox.DiagnosticProbe
import com.vpnproxy.core.singbox.DurabilityOptions
import com.vpnproxy.core.singbox.DurabilitySettings
import com.vpnproxy.smartconnect.AutoSwitcher
import com.vpnproxy.util.DebugFlags
import com.vpnproxy.core.diagnostics.BatteryMonitor
import com.vpnproxy.logging.CrashHandler
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.logging.LogEntry
import com.vpnproxy.logging.LogExporter
import com.vpnproxy.logging.LogLevel
import com.vpnproxy.logging.LogStats
import com.vpnproxy.logging.SensitiveMasker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BatteryUiState(
    val levelPercent: Int = -1,
    val temperatureC: Float = Float.NaN,
    val charging: Boolean = false,
    val currentDrawMa: Int? = null,
    val drainRatePerHour: Double? = null
)

@HiltViewModel
class DebugViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logCollector: LogCollector,
    private val logExporter: LogExporter,
    private val batteryMonitor: BatteryMonitor,
    private val batteryLogger: BatteryLogger,
    private val autoSwitcher: AutoSwitcher
) : ViewModel() {

    val filteredLogs: StateFlow<List<LogEntry>> = logCollector.filteredLogs
    val stats: StateFlow<LogStats> = logCollector.stats

    private val _journalInfo = MutableStateFlow<String?>(null)
    val journalInfo: StateFlow<String?> = _journalInfo.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _lastCrash = MutableStateFlow<String?>(null)
    val lastCrash: StateFlow<String?> = _lastCrash.asStateFlow()

    private val _diagnosticOptions = MutableStateFlow(DiagnosticSettings.load(context))
    val diagnosticOptions: StateFlow<DiagnosticOptions> = _diagnosticOptions.asStateFlow()

    private val _durabilityOptions = MutableStateFlow(DurabilitySettings.load(context))
    val durabilityOptions: StateFlow<DurabilityOptions> = _durabilityOptions.asStateFlow()

    private val _libboxTail = MutableStateFlow<String?>(null)
    val libboxTail: StateFlow<String?> = _libboxTail.asStateFlow()

    private val _probeResult = MutableStateFlow<String?>(null)
    val probeResult: StateFlow<String?> = _probeResult.asStateFlow()

    private val _battery = MutableStateFlow(BatteryUiState())
    val battery: StateFlow<BatteryUiState> = _battery.asStateFlow()

    private var currentSearch: String? = null
    private var currentLevel: LogLevel? = null

    init {
        _lastCrash.value = CrashHandler.readLastCrash(context)
        refreshJournalInfo()
        viewModelScope.launch {
            while (isActive) {
                refreshBattery()
                delay(30_000L)
            }
        }
    }

    private fun refreshBattery() {
        batteryMonitor.register()
        batteryMonitor.updateBaseline()
        _battery.value = BatteryUiState(
            levelPercent = batteryMonitor.getLevelPercent(),
            temperatureC = batteryMonitor.getTemperatureCelsius(),
            charging = batteryMonitor.isCharging(),
            currentDrawMa = batteryMonitor.getCurrentDrawMa(),
            drainRatePerHour = batteryMonitor.getDrainRatePerHour()
        )
    }

    fun updateDiagnosticOptions(options: DiagnosticOptions) {
        DiagnosticSettings.save(context, options)
        _diagnosticOptions.value = options
        _message.value = "Настройки диагностики сохранены; применятся при следующем подключении"
    }

    fun resetDiagnosticOptions() {
        val defaults = DiagnosticOptions()
        DiagnosticSettings.save(context, defaults)
        _diagnosticOptions.value = defaults
        _message.value = "Диагностические настройки сброшены; переподключитесь для применения"
    }

    fun updateDurabilityOptions(options: DurabilityOptions) {
        DurabilitySettings.save(context, options)
        _durabilityOptions.value = options
        _message.value = "Настройки устойчивости сохранены; применятся при следующем подключении"
    }

    fun resetDurabilityOptions() {
        val defaults = DurabilityOptions()
        DurabilitySettings.save(context, defaults)
        _durabilityOptions.value = defaults
        _message.value = "Настройки устойчивости сброшены; переподключитесь для применения"
    }

    fun showLastLibboxLines() {
        viewModelScope.launch {
            _libboxTail.value = withContext(Dispatchers.IO) {
                runCatching {
                    val callbackLines = logCollector.getAllEntries()
                        .asSequence()
                        .filter { it.tag == "LIBBOX" }
                        .takeLast(100)
                        .map { it.toFormattedString(maskSensitive = true) }
                        .toList()
                    val file = java.io.File(context.filesDir, "logs/libbox.log")
                    val fileLines = if (file.isFile) {
                        val tail = java.util.ArrayDeque<String>(100)
                        file.bufferedReader(Charsets.UTF_8).use { reader ->
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (tail.size == 100) tail.removeFirst()
                                tail.addLast(SensitiveMasker.mask(line))
                            }
                        }
                        tail.toList()
                    } else emptyList()
                    (callbackLines + fileLines).distinct().takeLast(150).joinToString("\n")
                        .ifBlank { "Native libbox has not emitted callback or file log lines yet (${file.absolutePath})" }
                }.getOrElse { "libbox log unavailable: ${it.javaClass.simpleName}: ${it.message}" }
            }
        }
    }

    fun runTrafficProbeNow() {
        viewModelScope.launch {
            val server = autoSwitcher.currentServer.value?.config?.name ?: "manual"
            _probeResult.value = "Проба выполняется…"
            _probeResult.value = try {
                DiagnosticProbe.runOnce(server, logCollector).toString()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "Probe failed: ${failure.javaClass.simpleName}: ${failure.message}"
            }
        }
    }

    fun logBatteryManual() {
        batteryLogger.logSnapshot("manual_from_ui")
        refreshBattery()
        _message.value = "BATTERY snapshot logged"
    }

    fun clearCrash() {
        CrashHandler.clearCrashes(context)
        _lastCrash.value = null
    }

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
        // Building the export reads the whole journal; keep it off the main thread so a
        // multi-megabyte journal cannot ANR the debug screen, and surface truncation.
        viewModelScope.launch {
            val text = try {
                withContext(Dispatchers.IO) { logExporter.buildExportText() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _message.value = "Ошибка копирования: ${t.javaClass.simpleName}: ${t.message}"
                return@launch
            }
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("VPNProxy Logs", text))
                _message.value = if (text.contains(LogExporter.TRUNCATION_MARKER)) {
                    "Скопировано (усечено до 2 МБ): ${text.length} символов"
                } else {
                    "Скопировано: ${text.length} символов"
                }
            } catch (t: Throwable) {
                _message.value = "Ошибка копирования: ${t.message}"
            }
        }
    }

    fun checkExport() {
        viewModelScope.launch {
            _message.value = try {
                withContext(Dispatchers.IO) { logExporter.checkExport() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                "Проверка экспорта завершилась ошибкой: ${failure.javaClass.simpleName}: ${failure.message}"
            }
            refreshJournalInfo()
        }
    }

    fun refreshJournalInfo() {
        _journalInfo.value = runCatching { logExporter.journalFileInfo() }.getOrNull()
    }

    fun export() {
        viewModelScope.launch {
            val uri = logExporter.exportToDownloads()
            _message.value = if (uri != null) {
                "Журнал сохранён в Downloads/"
            } else {
                "Не удалось сохранить журнал"
            }
            refreshJournalInfo()
        }
    }

    fun clear() {
        logCollector.clear()
        refreshJournalInfo()
    }
    fun clearMessage() { _message.value = null }
}
