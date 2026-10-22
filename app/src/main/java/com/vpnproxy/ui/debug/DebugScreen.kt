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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.vpnproxy.BuildConfig
import com.vpnproxy.core.singbox.DebugRuntimeOptions
import com.vpnproxy.core.singbox.RuntimeTunnelOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnproxy.logging.LogLevel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onBack: () -> Unit = {},
    viewModel: DebugViewModel = hiltViewModel()
) {
    val logs by viewModel.filteredLogs.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val lastCrash by viewModel.lastCrash.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf<LogLevel?>(null) }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var runtimeOptions by remember { mutableStateOf(DebugRuntimeOptions.load(context)) }
    fun saveOptions(updated: RuntimeTunnelOptions) {
        runtimeOptions = updated
        DebugRuntimeOptions.save(context, updated)
    }

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

            if (BuildConfig.DEBUG) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Runtime tunnel options (следующее подключение)", style = MaterialTheme.typography.titleSmall)
                        Text("DNS_MODE", style = MaterialTheme.typography.labelMedium)
                        DebugRuntimeOptions.dnsModes.forEach { mode ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = runtimeOptions.dnsMode == mode, onClick = {
                                    saveOptions(runtimeOptions.copy(dnsMode = mode))
                                })
                                Text(mode, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text("TUN_STACK", style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DebugRuntimeOptions.tunStacks.forEach { stack ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = runtimeOptions.tunStack == stack, onClick = {
                                        saveOptions(runtimeOptions.copy(tunStack = stack))
                                    })
                                    Text(stack)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = runtimeOptions.strictRoute, onCheckedChange = {
                                saveOptions(runtimeOptions.copy(strictRoute = it))
                            })
                            Text("STRICT_ROUTE")
                        }
                        Text("ROUTE_FINAL", style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            DebugRuntimeOptions.routeFinals.forEach { finalRoute ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = runtimeOptions.routeFinal == finalRoute, onClick = {
                                        saveOptions(runtimeOptions.copy(routeFinal = finalRoute))
                                    })
                                    Text(finalRoute)
                                }
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
