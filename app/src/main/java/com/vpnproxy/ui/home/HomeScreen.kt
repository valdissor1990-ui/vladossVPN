package com.vpnproxy.ui.home

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnproxy.R
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
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    var subscriptionMenuExpanded by remember { mutableStateOf(false) }

    var showServers by remember { mutableStateOf(false) }
    var pendingSelection by remember { mutableStateOf<com.vpnproxy.smartconnect.ScoringEngine.ScoredServer?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(Unit) { updateViewModel.checkOnce() }

    val bgBrush = remember {
        Brush.radialGradient(
            colors = listOf(DeepSpaceBlue, DeepSpaceVoid),
            radius = 1600f
        )
    }
    val stars = remember {
        val rnd = Random(42)
        List(50) {
            Offset(rnd.nextFloat(), rnd.nextFloat()) to rnd.nextFloat() * 0.4f + 0.15f
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("vladossVPN") },
                actions = {
                    IconButton(onClick = { showServers = true }) {
                        Icon(Icons.Default.List, "Servers")
                    }
                    IconButton(onClick = onNavigateToSubscriptions) {
                        Icon(Icons.Default.Cloud, "Subscriptions")
                    }
                    if (showDebug) {
                        IconButton(onClick = onNavigateToDebug) {
                            Icon(Icons.Default.BugReport, "Журнал логов")
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgBrush)
        ) {
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
                Text(
                    text = uiState.currentServerName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnDarkSecondary
                )

                Spacer(Modifier.height(28.dp))

                GlowPowerButton(
                    state = uiState.connectionState,
                    isBusy = uiState.isBusy,
                    isScanning = uiState.isScanningServers,
                    onClick = {
                        if (uiState.isScanningServers) {
                            Log.i("HomeScreen", "HomeScreen: stop scan from main button")
                            viewModel.cancelServerScan()
                        } else {
                            viewModel.toggleConnection()
                        }
                    }
                )

                if (uiState.isScanningServers) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Поиск доступных серверов: ${uiState.scanProgressPercent}%",
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
                    Spacer(Modifier.height(8.dp))
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

    if (uiState.isScanningServers) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelServerScan() },
            containerColor = Color.White,
            titleContentColor = Color(0xFF172033),
            textContentColor = Color(0xFF334155),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Поиск доступных серверов", color = Color(0xFF172033))
                    TextButton(onClick = { viewModel.cancelServerScan() }) {
                        Text("✕", color = Color(0xFF64748B), style = MaterialTheme.typography.titleMedium)
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(
                            progress = uiState.scanProgressPercent.coerceIn(0, 100) / 100f,
                            color = Color(0xFF2563EB),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Text("Сканирование: ${uiState.scanProgressPercent}%", color = Color(0xFF334155))
                    }
                    Text(
                        text = "Найдено доступных: ${uiState.scannedAvailableServers}" +
                            if (uiState.scanTotalServers > 0) " из ${uiState.scanTotalServers}" else "",
                        color = Color(0xFF334155)
                    )
                    Text(
                        "Крестик или «Остановить» — сканирование прерывается.",
                        color = Color(0xFF64748B),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelServerScan() }) {
                    Text("Остановить", color = Color(0xFF2563EB))
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
