package com.vpnproxy.ui.debug

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnproxy.logging.LogLevel
import com.vpnproxy.BuildConfig
import com.vpnproxy.core.singbox.DiagnosticSettings
import com.vpnproxy.core.singbox.DurabilityOptions
import com.vpnproxy.core.singbox.DurabilitySettings
import com.vpnproxy.util.DebugFlags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onBack: () -> Unit = {},
    viewModel: DebugViewModel = hiltViewModel()
) {
    val logs by viewModel.filteredLogs.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val lastCrash by viewModel.lastCrash.collectAsStateWithLifecycle()
    val battery by viewModel.battery.collectAsStateWithLifecycle()
    val diagnosticOptions by viewModel.diagnosticOptions.collectAsStateWithLifecycle()
    val durabilityOptions by viewModel.durabilityOptions.collectAsStateWithLifecycle()
    val libboxTail by viewModel.libboxTail.collectAsStateWithLifecycle()
    val probeResult by viewModel.probeResult.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val journalInfo by viewModel.journalInfo.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf<LogLevel?>(null) }
    var levelMenuExpanded by remember { mutableStateOf(false) }
    var showDiagnosticControls by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Журнал отладки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.copyAll() }) {
                        Icon(Icons.Default.ContentCopy, "Копировать всё")
                    }
                    IconButton(onClick = { viewModel.export() }) {
                        Icon(Icons.Default.Download, "Экспорт лога")
                    }
                    IconButton(onClick = { viewModel.clear() }) {
                        Icon(Icons.Default.DeleteSweep, "Очистить")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Keep the effective-settings summary pinned above the scrollable content.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Effective settings", style = MaterialTheme.typography.titleSmall)
                    Text("DNS=${diagnosticOptions.dnsMode}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("TUN=${diagnosticOptions.tunStack} strict=${diagnosticOptions.strictRoute}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("Route=${diagnosticOptions.routeFinal}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("DisallowSelf=${diagnosticOptions.useDisallowSelfPackage}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("AutoDetect=${diagnosticOptions.autoDetectInterface}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("Probe=${diagnosticOptions.passiveProbeEnabled} Freeze=${diagnosticOptions.freezeAutoSwitcherAfterConnected}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }

            // Primary journal actions, pinned so they are always reachable: save a file and copy to clipboard.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { viewModel.export() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Скачать")
                }
                Button(onClick = { viewModel.copyAll() }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Скопировать")
                }
            }

            Text(
                text = "Всего: ${stats.total} · WARN: ${stats.count(LogLevel.WARN)} · ERROR: ${stats.count(LogLevel.ERROR)} · FATAL: ${stats.count(LogLevel.FATAL)} · отброшено: ${stats.droppedEntries}",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            journalInfo?.let { info ->
                Text(
                    text = info,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .imePadding(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (lastCrash != null) {
                    item(key = "last-crash") {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Последний краш",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = { viewModel.clearCrash() }) {
                                        Icon(Icons.Default.Delete, "Clear")
                                    }
                                }
                                Text(
                                    text = lastCrash!!.take(1500),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                item(key = "battery-card") {
                    BatteryCard(state = battery, onLog = { viewModel.logBatteryManual() })
                }

                if (BuildConfig.DEBUG && DebugFlags.diagnosticEnabled()) {
                    item(key = "diagnostic-toggle") {
                        OutlinedButton(
                            onClick = { showDiagnosticControls = !showDiagnosticControls },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (showDiagnosticControls) "Скрыть настройки диагностики" else "Настройки диагностики")
                        }
                    }
                    if (showDiagnosticControls) {
                        item(key = "diagnostic-controls") {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text("Настройки диагностики (следующее подключение)", style = MaterialTheme.typography.titleSmall)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = diagnosticOptions.useDisallowSelfPackage, onCheckedChange = {
                                            viewModel.updateDiagnosticOptions(diagnosticOptions.copy(useDisallowSelfPackage = it))
                                        })
                                        Text("USE_DISALLOW_SELF_PACKAGE")
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = diagnosticOptions.autoDetectInterface, onCheckedChange = {
                                            viewModel.updateDiagnosticOptions(diagnosticOptions.copy(autoDetectInterface = it))
                                        })
                                        Text("AUTO_DETECT_INTERFACE")
                                    }
                                    Text("DNS_MODE", style = MaterialTheme.typography.labelMedium)
                                    DiagnosticSettings.dnsModes.forEach { mode ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = diagnosticOptions.dnsMode == mode, onClick = {
                                                viewModel.updateDiagnosticOptions(diagnosticOptions.copy(dnsMode = mode))
                                            })
                                            Text(mode, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    Text("TUN_STACK", style = MaterialTheme.typography.labelMedium)
                                    DiagnosticSettings.tunStacks.forEach { stack ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = diagnosticOptions.tunStack == stack, onClick = {
                                                viewModel.updateDiagnosticOptions(diagnosticOptions.copy(tunStack = stack))
                                            })
                                            Text(stack)
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = diagnosticOptions.strictRoute, onCheckedChange = {
                                            viewModel.updateDiagnosticOptions(diagnosticOptions.copy(strictRoute = it))
                                        })
                                        Text("STRICT_ROUTE")
                                    }
                                    Text("ROUTE_FINAL", style = MaterialTheme.typography.labelMedium)
                                    DiagnosticSettings.routeFinals.forEach { route ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(selected = diagnosticOptions.routeFinal == route, onClick = {
                                                viewModel.updateDiagnosticOptions(diagnosticOptions.copy(routeFinal = route))
                                            })
                                            Text(route)
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = diagnosticOptions.passiveProbeEnabled, onCheckedChange = {
                                            viewModel.updateDiagnosticOptions(diagnosticOptions.copy(passiveProbeEnabled = it))
                                        })
                                        Text("PASSIVE_PROBE_ENABLED")
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked = diagnosticOptions.freezeAutoSwitcherAfterConnected, onCheckedChange = {
                                            viewModel.updateDiagnosticOptions(diagnosticOptions.copy(freezeAutoSwitcherAfterConnected = it))
                                        })
                                        Text("FREEZE_AUTOSWITCHER_AFTER_CONNECTED")
                                    }
                                    Button(onClick = { viewModel.runTrafficProbeNow() }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Run traffic probe now")
                                    }
                                    Button(onClick = { viewModel.showLastLibboxLines() }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Показать последние 100 строк libbox log")
                                    }
                                    Button(onClick = { viewModel.checkExport() }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Проверить экспорт")
                                    }
                                    Button(onClick = { viewModel.export() }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Экспорт лога")
                                    }
                                    Button(
                                        onClick = { viewModel.resetDiagnosticOptions() },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                    ) {
                                        Text("Сбросить диагностические настройки")
                                    }
                                    probeResult?.let {
                                        Text("Probe: $it", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                    }
                                    libboxTail?.let {
                                        Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                    item(key = "durability-controls") {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Устойчивость соединения (следующее подключение)", style = MaterialTheme.typography.titleSmall)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = durabilityOptions.nativeUrlTestEnabled, onCheckedChange = {
                                        viewModel.updateDurabilityOptions(durabilityOptions.copy(nativeUrlTestEnabled = it))
                                    })
                                    Text("NATIVE_URL_TEST_GROUP")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Standby: ${durabilityOptions.urlTestStandbyCount}")
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedButton(onClick = {
                                        viewModel.updateDurabilityOptions(
                                            durabilityOptions.copy(urlTestStandbyCount = (durabilityOptions.urlTestStandbyCount - 1).coerceAtLeast(0))
                                        )
                                    }) { Text("-") }
                                    Spacer(Modifier.width(4.dp))
                                    OutlinedButton(onClick = {
                                        viewModel.updateDurabilityOptions(
                                            durabilityOptions.copy(
                                                urlTestStandbyCount = (durabilityOptions.urlTestStandbyCount + 1)
                                                    .coerceAtMost(DurabilityOptions.MAX_STANDBY_COUNT)
                                            )
                                        )
                                    }) { Text("+") }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = durabilityOptions.tlsFragmentEnabled, onCheckedChange = {
                                        viewModel.updateDurabilityOptions(durabilityOptions.copy(tlsFragmentEnabled = it))
                                    })
                                    Text("TLS_FRAGMENT")
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = durabilityOptions.tlsRecordFragmentEnabled, onCheckedChange = {
                                        viewModel.updateDurabilityOptions(durabilityOptions.copy(tlsRecordFragmentEnabled = it))
                                    })
                                    Text("TLS_RECORD_FRAGMENT")
                                }
                                Text("UTLS_FINGERPRINT", style = MaterialTheme.typography.labelMedium)
                                DurabilitySettings.utlsFingerprints.forEach { fingerprint ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = durabilityOptions.utlsFingerprint == fingerprint,
                                            onClick = {
                                                viewModel.updateDurabilityOptions(durabilityOptions.copy(utlsFingerprint = fingerprint))
                                            }
                                        )
                                        Text(fingerprint, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                TextButton(onClick = {
                                    viewModel.updateDurabilityOptions(durabilityOptions.copy(utlsFingerprint = null))
                                }) {
                                    Text("UTLS: по умолчанию (профиль/провайдер)")
                                }
                                Button(onClick = { viewModel.resetDurabilityOptions() }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Сбросить настройки устойчивости")
                                }
                            }
                        }
                    }
                }

                item(key = "log-search-filter") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                viewModel.setSearch(it)
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Поиск по тегу или тексту") },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            singleLine = true
                        )
                        Spacer(Modifier.width(8.dp))
                        Box {
                            OutlinedButton(onClick = { levelMenuExpanded = true }) {
                                Text(selectedLevel?.name ?: "ALL")
                            }
                            DropdownMenu(
                                expanded = levelMenuExpanded,
                                onDismissRequest = { levelMenuExpanded = false }
                            ) {
                                (listOf<LogLevel?>(null) + LogLevel.entries.toList()).forEach { level ->
                                    DropdownMenuItem(
                                        text = { Text(level?.name ?: "ALL") },
                                        onClick = {
                                            selectedLevel = level
                                            viewModel.setLevelFilter(level)
                                            levelMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                items(logs.reversed(), key = { it.id }) { entry ->
                    LogEntryItem(entry)
                }
            }
        }
    }
}

@Composable
private fun BatteryCard(
    state: BatteryUiState,
    onLog: () -> Unit
) {
    val tempColor = when {
        state.temperatureC.isNaN() -> MaterialTheme.colorScheme.onSurface
        state.temperatureC < 35f -> Color(0xFF4CAF50)
        state.temperatureC <= 42f -> Color(0xFFFFC107)
        else -> Color(0xFFF44336)
    }
    val tempText = if (state.temperatureC.isNaN()) "N/A" else String.format("%.1f°C", state.temperatureC)
    val drainText = when {
        state.charging -> "Заряжается"
        state.drainRatePerHour != null -> String.format("%.1f %%/h", state.drainRatePerHour)
        else -> "N/A"
    }
    val drawText = state.currentDrawMa?.let { "$it mA" } ?: "N/A"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Батарея",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
            Text("Уровень: ${if (state.levelPercent >= 0) "${state.levelPercent}%" else "N/A"}")
            Text(
                text = "Температура: $tempText",
                color = tempColor
            )
            Text("Зарядка: ${if (state.charging) "да" else "нет"}")
            Text("Ток: $drawText")
            Text("Разряд: $drainText")
            Spacer(Modifier.height(8.dp))
            Button(onClick = onLog) {
                Icon(Icons.Default.BatteryChargingFull, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Залогировать батарею")
            }
        }
    }
}

@Composable
private fun LogEntryItem(entry: com.vpnproxy.logging.LogEntry) {
    val color = when (entry.level) {
        LogLevel.TRACE, LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.FATAL -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.level.name,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.tag,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = entry.timeString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
            Text(
                text = entry.message,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
            entry.stackTrace?.let { stack ->
                Text(
                    text = stack,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
