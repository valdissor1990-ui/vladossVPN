package com.vpnproxy.ui.home

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnproxy.ui.components.ConnectionStatsCard
import com.vpnproxy.ui.components.GlowPowerButton
import com.vpnproxy.ui.servers.ServerListContent
import com.vpnproxy.ui.theme.*
import com.vpnproxy.ui.update.UpdateDialog
import com.vpnproxy.ui.update.UpdateViewModel
import kotlin.random.Random
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
    onNavigateToSubscriptions: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    showDebug: Boolean = true,
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val traffic by viewModel.traffic.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()

    var showServers by remember { mutableStateOf(false) }
    var showScanDialog by remember { mutableStateOf(false) }
    var pendingSelection by remember { mutableStateOf<com.vpnproxy.smartconnect.ScoringEngine.ScoredServer?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { updateViewModel.checkOnce() }
    LaunchedEffect(uiState.isScanningServers) {
        showScanDialog = uiState.isScanningServers
    }

    val bgBrush = remember {
        Brush.radialGradient(colors = listOf(DeepSpaceBlue, DeepSpaceVoid), radius = 1600f)
    }
    val stars = remember {
        val rnd = Random(42)
        List(50) { Offset(rnd.nextFloat(), rnd.nextFloat()) to rnd.nextFloat() * 0.4f + 0.15f }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("vladossVPN") },
                actions = {
                    IconButton(onClick = { showServers = true }) { Icon(Icons.Default.List, "Servers") }
                    IconButton(onClick = onNavigateToSubscriptions) { Icon(Icons.Default.Cloud, "Subscriptions") }
                    if (showDebug) {
                        IconButton(onClick = onNavigateToDebug) { Icon(Icons.Default.BugReport, "Журнал логов") }
                    }
                    IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        bottomBar = {
            NavigationBar(containerColor = CosmosSurface) {
                NavigationBarItem(
                    selected = showServers,
                    onClick = { showServers = true },
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    label = { Text("Серверы") }
                )
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().background(bgBrush)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                stars.forEach { (offset, alpha) ->
                    drawCircle(
                        color = Color.White.copy(alpha = alpha),
                        radius = 1.5f,
                        center = Offset(offset.x * size.width, offset.y * size.height)
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = when (uiState.connectionState) {
                        ConnectionUiState.CONNECTED -> "Подключено"
                        ConnectionUiState.UNSTABLE -> "Нестабильно"
                        ConnectionUiState.CONNECTING, ConnectionUiState.VERIFYING -> "Подключение…"
                        ConnectionUiState.ERROR -> "Ошибка"
                        else -> "Отключено"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = OnDark
                )
                Spacer(Modifier.height(8.dp))
                Text(text = uiState.currentServerName, style = MaterialTheme.typography.bodyMedium, color = OnDarkSecondary)
                Spacer(Modifier.height(28.dp))
                GlowPowerButton(
                    state = uiState.connectionState,
                    isScanning = uiState.isScanningServers,
                    onClick = {
                        if (uiState.isScanningServers) {
                            Log.i("HomeScreen", "stop scan from main button")
                            viewModel.cancelServerScan()
                        } else {
                            viewModel.toggleConnection()
                        }
                    }
                )
                if (uiState.isScanningServers) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Поиск серверов: ${uiState.scannedAvailableServers}" +
                                if (uiState.scanTotalServers > 0) " / ${uiState.scanTotalServers}" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                ConnectionStatsCard(
                    state = uiState.connectionState,
                    serverName = uiState.currentServerName,
                    serverRtt = uiState.currentRtt,
                    traffic = traffic,
                    sessionStartedAt = uiState.sessionStartedAt
                )
                Spacer(Modifier.height(16.dp))
                uiState.lastErrorMessage?.let {
                    Text(it, color = Color(0xFFF87171), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    LaunchedEffect(pendingSelection) {
        pendingSelection?.let { server ->
            delay(250)
            viewModel.onServerSelected(server)
            pendingSelection = null
        }
    }

    if (showServers) {
        ModalBottomSheet(
            onDismissRequest = { showServers = false },
            sheetState = sheetState,
            containerColor = CosmosSurface,
            contentColor = OnDark
        ) {
            ServerListContent(
                onServerSelected = { server ->
                    pendingSelection = server
                    showServers = false
                }
            )
        }
    }

    if (uiState.isScanningServers && showScanDialog) {
        AlertDialog(
            onDismissRequest = { showScanDialog = false },
            containerColor = Color.White,
            titleContentColor = Color(0xFF172033),
            textContentColor = Color(0xFF334155),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Проверка серверов", color = Color(0xFF172033))
                    IconButton(onClick = { showScanDialog = false }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Закрыть окно сканирования",
                            tint = Color(0xFF64748B)
                        )
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = Color(0xFF2563EB), strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                        Text(
                            "Проверка выполняется…",
                            color = Color(0xFF334155)
                        )
                    }
                    HorizontalDivider(color = Color(0xFFE2E8F0))
                    Text("Всего серверов: ${uiState.scanTotalServers}", color = Color(0xFF172033))
                    Text("Проверено: ${uiState.scanCheckedServers}", color = Color(0xFF334155))
                    Text("Доступно: ${uiState.scannedAvailableServers}", color = Color(0xFF166534))
                    Text("Не прошло проверку: ${uiState.scanFailedServers}", color = Color(0xFFB91C1C))
                    Text(
                        "Крестик закрывает окно, но не останавливает проверку.",
                        color = Color(0xFF64748B),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancelServerScan()
                    showScanDialog = false
                }) {
                    Text("Остановить тест", color = Color(0xFFB91C1C))
                }
            }
        )
    }

    UpdateDialog(
        state = updateState,
        onDownload = { updateViewModel.download() },
        onInstall = { updateViewModel.install() },
        onDismiss = { updateViewModel.dismiss() }
    )
}
