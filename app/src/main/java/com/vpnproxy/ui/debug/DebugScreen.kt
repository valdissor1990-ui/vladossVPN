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
    val libboxTail by viewModel.libboxTail.collectAsStateWithLifecycle()
    val probeResult by viewModel.probeResult.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf<LogLevel?>(null) }
    var showDiagnosticControls by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

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
                        Icon(Icons.Default.Download, "Экспорт")
                    }
                    IconButton(onClick = { viewModel.clear() }) {
                        Icon(Icons.Default.DeleteSweep, "Очистить")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (lastCrash != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.BugReport,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
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

            BatteryCard(
                state = battery,
                onLog = { viewModel.logBatteryManual() }
            )

            if (BuildConfig.DEBUG && DebugFlags.diagnosticEnabled()) {
                OutlinedButton(onClick = { showDiagnosticControls = !showDiagnosticControls }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(if (showDiagnosticControls) "Скрыть настройки диагностики" else "Настройки диагностики")
                }
                if (showDiagnosticControls) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("Настройки диагностики (следующее подключение)", style = MaterialTheme.typography.titleSmall)
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DiagnosticSettings.tunStacks.forEach { stack ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = diagnosticOptions.tunStack == stack, onClick = {
                                        viewModel.updateDiagnosticOptions(diagnosticOptions.copy(tunStack = stack))
                                    })
                                    Text(stack)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = diagnosticOptions.strictRoute, onCheckedChange = {
                                viewModel.updateDiagnosticOptions(diagnosticOptions.copy(strictRoute = it))
                            })
                            Text("STRICT_ROUTE")
                        }
                        Text("ROUTE_FINAL", style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DiagnosticSettings.routeFinals.forEach { route ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = diagnosticOptions.routeFinal == route, onClick = {
                                        viewModel.updateDiagnosticOptions(diagnosticOptions.copy(routeFinal = route))
                                    })
                                    Text(route)
                                }
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.showLastLibboxLines() }) {
                                Text("Показать последние 100 строк libbox log")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.runTrafficProbeNow() }) {
                                Text("Run traffic probe now")
                            }
                        }
                        probeResult?.let { Text("Probe: $it", fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                        libboxTail?.let {
                            Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 100)
                        }
                    }
                }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        viewModel.setSearch(it)
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Поиск…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = selectedLevel != null,
                    onClick = {
                        selectedLevel = if (selectedLevel == null) LogLevel.WARN else null
                        viewModel.setLevelFilter(selectedLevel)
                    },
                    label = { Text(selectedLevel?.name ?: "ALL") }
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp)
            ) {
                items(logs.reversed()) { entry ->
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
            Row {
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
            }
            Text(
                text = entry.message,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
