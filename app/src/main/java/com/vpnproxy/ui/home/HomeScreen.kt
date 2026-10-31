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
    onOpenSettings: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val googleRtt by viewModel.googleRtt.collectAsStateWithLifecycle()
    val latencyBreakdown by viewModel.latencyBreakdown.collectAsStateWithLifecycle()

    var showServers by remember { mutableStateOf(false) }
    var pendingSelection by remember { mutableStateOf<com.vpnproxy.smartconnect.ScoringEngine.ScoredServer?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(pendingSelection) {
        pendingSelection?.let { server ->
            delay(250)
            viewModel.onServerSelected(server)
            pendingSelection = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0B1220), Color(0xFF111827), Color(0xFF0F172A))
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = OnDark)
                }
                IconButton(onClick = onOpenSubscriptions) {
                    Icon(Icons.Default.Cloud, contentDescription = "Subscriptions", tint = OnDark)
                }
            }

            Spacer(Modifier.height(24.dp))

            GlowPowerButton(
                isConnected = uiState.connectionState == HomeViewModel.ConnectionUiState.CONNECTED ||
                    uiState.connectionState == HomeViewModel.ConnectionUiState.UNSTABLE,
                isConnecting = uiState.connectionState == HomeViewModel.ConnectionUiState.CONNECTING,
                isScanning = uiState.isScanningServers,
                onClick = {
                    when {
                        uiState.isScanningServers -> {
                            Log.i("HomeScreen", "HomeScreen: stop scan from main button")
                            viewModel.cancelServerScan()
                        }
                        else -> viewModel.toggleConnection()
                    }
                }
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = when (uiState.connectionState) {
                    HomeViewModel.ConnectionUiState.CONNECTED -> "Подключено"
                    HomeViewModel.ConnectionUiState.UNSTABLE -> "Нестабильно"
                    HomeViewModel.ConnectionUiState.CONNECTING -> "Подключение..."
                    HomeViewModel.ConnectionUiState.ERROR -> "Ошибка"
                    else -> "Отключено"
                },
                style = MaterialTheme.typography.titleLarge,
                color = OnDark
            )

            uiState.selectedServerName?.let {
                Text(text = it, color = OnDarkSecondary, style = MaterialTheme.typography.bodyMedium)
            }

            if (uiState.isScanningServers) {
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Поиск серверов: ${uiState.scanProgressPercent}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            ConnectionStatsCard(
                rttMs = googleRtt,
                breakdown = latencyBreakdown,
                sessionStartedAt = uiState.sessionStartedAt
            )

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = { showServers = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.List, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Список серверов")
            }

            uiState.errorMessage?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Color(0xFFF87171))
            }
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            progress = uiState.scanProgressPercent.coerceIn(0, 100) / 100f,
                            color = Color(0xFF2563EB),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            "Сканирование: ${uiState.scanProgressPercent}%",
                            color = Color(0xFF334155)
                        )
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
