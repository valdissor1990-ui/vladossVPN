package com.vpnproxy.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnproxy.ace.AceControlPlane
import com.vpnproxy.ace.AceNativeBridge
import com.vpnproxy.core.diagnostics.BatteryLogger
import com.vpnproxy.core.singbox.DiagnosticOptions
import com.vpnproxy.core.singbox.DiagnosticSettings
import com.vpnproxy.core.singbox.DiagnosticProbe
import com.vpnproxy.smartconnect.AutoSwitcher
import com.vpnproxy.util.DebugFlags
import com.vpnproxy.core.diagnostics.BatteryMonitor
import com.vpnproxy.logging.CrashHandler
import com.vpnproxy.logging.LogCollector
import com.vpnproxy.logging.LogEntry
import com.vpnproxy.logging.LogExporter
import com.vpnproxy.logging.LogLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
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

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _lastCrash = MutableStateFlow<String?>(null)
    val lastCrash: StateFlow<String?> = _lastCrash.asStateFlow()

    private val _diagnosticOptions = MutableStateFlow(DiagnosticSettings.load(context))
    val diagnosticOptions: StateFlow<DiagnosticOptions> = _diagnosticOptions.asStateFlow()

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

    fun showLastLibboxLines() {
        _libboxTail.value = runCatching {
            val callbackLines = logCollector.getAllEntries()
                .filter { it.tag == "LIBBOX" }
                .takeLast(100)
                .joinToString("\n") { it.toFormattedString(maskSensitive = true) }
            if (callbackLines.isNotBlank()) callbackLines
            else {
                val file = java.io.File(context.filesDir, "logs/libbox.log")
                if (!file.isFile) "No native callback lines yet; file log.output is disabled (${file.absolutePath})"
                else file.readLines(Charsets.UTF_8).takeLast(100).joinToString("\n").ifBlank { "native libbox file is empty" }
            }
        }.getOrElse { "libbox log unavailable: ${it.javaClass.simpleName}: ${it.message}" }
    }

    fun runTrafficProbeNow() {
        viewModelScope.launch {
            val server = autoSwitcher.currentServer.value?.config?.name ?: "manual"
            _probeResult.value = "Проба выполняется…"
            _probeResult.value = runCatching { DiagnosticProbe.runOnce(server, logCollector).toString() }
                .getOrElse { "Probe failed: ${it.javaClass.simpleName}: ${it.message}" }
        }
    }

    fun logBatteryManual() {
        batteryLogger.logSnapshot("manual_from_ui")
        refreshBattery()
        _message.value = "BATTERY snapshot logged"
    }

    /** Stage B: start isolated :ace process (no VPN control). */
    fun startAceStageB() {
        runCatching {
            AceControlPlane.start(context)
            logCollector.info("AceControlPlane", "Stage B Start ACE requested; nativeLoaded=${AceNativeBridge.isNativeLoaded()}")
            _message.value = "ACE start requested (see logcat AceProcessService)"
        }.onFailure {
            _message.value = "ACE start failed: ${it.message}"
            logCollector.error("AceControlPlane", "Stage B start failed", it)
        }
    }

    /** Stage B: stop :ace process. */
    fun stopAceStageB() {
        runCatching {
            AceControlPlane.stop(context)
            logCollector.info("AceControlPlane", "Stage B Stop ACE requested")
            _message.value = "ACE stop requested"
        }.onFailure {
            _message.value = "ACE stop failed: ${it.message}"
        }
    }

    /** Stage B: intentional panic via native bridge (if .so loaded). */
    fun triggerAcePanicStageB() {
        val result = AceNativeBridge.triggerTestPanic()
        logCollector.info(
            "AceControlPlane",
            "Stage B panic test: success=${result.isSuccess} error=${result.exceptionOrNull()?.message}"
        )
        _message.value = if (result.isSuccess) {
            "Panic returned Ok (unexpected)"
        } else {
            "Panic contained or stub: ${result.exceptionOrNull()?.message}"
        }
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
        try {
            val text = logExporter.buildExportText()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("VPNProxy Logs", text))
            _message.value = "Скопировано: ${text.length} символов"
        } catch (t: Throwable) {
            _message.value = "Ошибка копирования: ${t.message}"
        }
    }

    fun checkExport() {
        viewModelScope.launch {
            _message.value = runCatching { logExporter.checkExport() }
                .getOrElse { "Проверка экспорта завершилась ошибкой: ${it.javaClass.simpleName}: ${it.message}" }
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
