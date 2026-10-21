package com.vpnproxy.ui.subscriptions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.vpnproxy.subscription.SubscriptionRepository
import com.vpnproxy.ui.theme.OnDark
import com.vpnproxy.ui.theme.WarningYellow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    onBack: () -> Unit,
    onNavigateToServers: () -> Unit,
    viewModel: SubscriptionsViewModel = hiltViewModel()
) {
    val subs by viewModel.subscriptions.collectAsStateWithLifecycle()
    val configs by viewModel.allConfigs.collectAsStateWithLifecycle()
    val refreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val usingCache by viewModel.usingCache.collectAsStateWithLifecycle()
    val scannedServers by viewModel.scannedServers.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val reachableKeys = remember(scannedServers) {
        scannedServers.asSequence()
            .filter { it.successRate > 0.5f }
            .map { it.config.connectionKey() }
            .toSet()
    }

    var dismissCacheBanner by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    LaunchedEffect(usingCache) {
        if (!usingCache) dismissCacheBanner = false
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Подписки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { onNavigateToServers() }) {
                        Icon(Icons.Default.List, "Все серверы")
                    }
                    IconButton(onClick = { viewModel.refreshAll() }, enabled = !refreshing) {
                        if (refreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, "Обновить все")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddDialog = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Добавить") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (usingCache && !dismissCacheBanner) {
                item(key = "cache-warning") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = WarningYellow.copy(alpha = 0.14f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⚠️ Источник недоступен, используются кэшированные серверы (${reachableKeys.size} доступно)",
                                color = WarningYellow,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            TextButton(onClick = { dismissCacheBanner = true }) {
                                Text("Скрыть", color = OnDark)
                            }
                        }
                    }
                }
            }
            item(key = "unique-server-summary") {
                Text(
                    text = if (isScanning) "Проверка серверов… Доступно: ${reachableKeys.size}" else "Доступно уникальных серверов: ${reachableKeys.size}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                )
            }
            if (subs.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "Нет подписок.\nНажмите «Добавить», чтобы ввести URL провайдера.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            items(subs, key = { it.id }) { sub ->
                val countForSub = configs.asSequence()
                    .filter { it.providerTag == sub.name }
                    .map { it.connectionKey() }
                    .distinct()
                    .count { it in reachableKeys }
                val canRemove = sub.id != SubscriptionRepository.DEFAULT_SUBSCRIPTION_ID
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = sub.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (canRemove) {
                                IconButton(onClick = { viewModel.removeSubscription(sub.id) }) {
                                    Icon(Icons.Default.Delete, "Удалить")
                                }
                            }
                        }
                        Text(text = sub.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        Text(text = "Тег: ${sub.displayTag()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(6.dp))
                        Text(text = "Серверов: $countForSub", style = MaterialTheme.typography.labelMedium, fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var url by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var tag by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Добавить подписку") },
            text = {
                Column {
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL подписки") }, singleLine = false, maxLines = 3, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название (опционально)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = tag, onValueChange = { tag = it }, label = { Text("Тег (опционально)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.addSubscription(url.trim(), name.trim(), tag.trim()); showAddDialog = false }, enabled = url.isNotBlank()) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Отмена") } }
        )
    }
}
