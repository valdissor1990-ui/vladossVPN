package com.vpnproxy.ui.debug

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnproxy.logging.LogLevel

@Composable
fun DebugScreen(viewModel: DebugViewModel = hiltViewModel()) {
    val logs by viewModel.filteredLogs.collectAsStateWithLifecycle()
    var searchQuery by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf<LogLevel?>(null) }
    val listState = rememberLazyListState()

    // Автоскролл при новых записях
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Журнал отладки") },
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
            // Фильтры
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

            // Список логов
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
