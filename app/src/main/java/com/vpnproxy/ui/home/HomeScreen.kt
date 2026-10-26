package com.vpnproxy.ui.home

import com.vpnproxy.R

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

    var showServers by remember { mutableStateOf(false) }
    var subscriptionMenuExpanded by remember { mutableStateOf(false) }
    var pendingSelection by remember { mutableStateOf<com.vpnproxy.smartconnect.ScoringEngine.ScoredServer?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(modifier = Modifier.fillMaxSize()) {
        // Background stars (simplified from original)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(Brush.verticalGradient(listOf(CosmosDeep, CosmosBg, CosmosDeep), startY = 0f, endY = h))
            repeat(40) {
                val x = Random(it * 17).nextFloat() * w
                val y = Random(it * 31).nextFloat() * h
                drawCircle(Color.White.copy(alpha = 0.15f + (it % 5) * 0.05f), radius = 1.2f + (it % 3), center = Offset(x, y))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("vladossVPN", style = MaterialTheme.typography.titleLarge, color = OnDark)
                Row {
                    IconButton(onClick = { showServers = true }) {
                        Icon(Icons.Default.List, contentDescription = "Servers", tint = OnDark)
                    }
                    IconButton(onClick = onNavigateToSubscriptions) {
                        Icon(Icons.Default.Cloud, contentDescription = "Subscriptions", tint = OnDark)
                    }
                    if (showDebug) {
                        IconButton(onClick = onNavigateToDebug) {
                            Icon(Icons.Default.BugReport, contentDescription = "Debug", tint = OnDark)
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = OnDark)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (uiState.connectionState == ConnectionUiState.ERROR) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = DisconnectRed.copy(alpha = 0.15f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = DisconnectRed)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = uiState.lastErrorMessage?.takeIf { it.isNotBlank() }
                                    ?: "Не удалось подключиться",
                                color = OnDark,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { viewModel.retryConnect() },
                            colors = ButtonDefaults.buttonColors(containerColor = GlowBlue)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Повторить")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            GlowPowerButton(
                state = uiState.connectionState,
                enabled = !uiState.isBusy || uiState.isScanningServers,
                isScanning = uiState.isScanningServers,
                onClick = {
                    if (uiState.isScanningServers) viewModel.cancelServerScan()
                    else viewModel.toggleConnection()
                }
            )

            val canSwitchServer = uiState.connectionState == ConnectionUiState.CONNECTED ||
                uiState.connectionState == ConnectionUiState.UNSTABLE
            if (canSwitchServer) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { viewModel.switchServer() },
                    enabled = !uiState.isBusy,
                    modifier = Modifier.fillMaxWidth(0.72f)
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = OnDark
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.switch_server),
                        color = OnDark
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CosmosSurface.copy(alpha = 0.9f))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("Подписка для автоподбора серверов", style = MaterialTheme.typography.labelMedium, color = OnDarkSecondary)
                    Box {
                        TextButton(
                            onClick = { subscriptionMenuExpanded = true },
                            enabled = !uiState.isBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val selectedName = when {
                                !uiState.isAutomaticMode -> "Ручной выбор"
                                uiState.selectedSubscriptionId == HomeViewModel.ALL_SUBSCRIPTIONS_ID -> "Все подписки"
                                else -> subscriptions.firstOrNull { it.id == uiState.selectedSubscriptionId }?.name
                                    ?.let { "Только: $it" }
                                    ?: "Подписка недоступна"
                            }
                            Text(selectedName, color = OnDark, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ExpandMore, contentDescription = null, tint = OnDark)
                        }
                        DropdownMenu(
                            expanded = subscriptionMenuExpanded,
                            onDismissRequest = { subscriptionMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Все подписки") },
                                onClick = {
                                    viewModel.setAutomaticSubscription(HomeViewModel.ALL_SUBSCRIPTIONS_ID)
                                    subscriptionMenuExpanded = false
                                }
                            )
                            subscriptions.forEach { subscription ->
                                DropdownMenuItem(
                                    text = { Text(subscription.name) },
                                    onClick = {
                                        viewModel.setAutomaticSubscription(subscription.id)
                                        subscriptionMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CosmosSurface.copy(alpha = 0.9f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (uiState.isAutomaticMode) "Автоматический режим" else "Ручной выбор сервера",
                            style = MaterialTheme.typography.titleSmall,
                            color = OnDark
                        )
                        Text(
                            text = if (uiState.isAutomaticMode) "Приложение выберет сервер само" else "Выберите сервер в списке ниже",
                            style = MaterialTheme.typography.bodySmall,
                            color = OnDarkSecondary
                        )
                    }
                    Switch(
                        checked = uiState.isAutomaticMode,
                        onCheckedChange = viewModel::setAutomaticMode,
                        enabled = !uiState.isBusy
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            ConnectionStatsCard(
                state = uiState.connectionState,
                sessionStartedAt = uiState.sessionStartedAt,
                serverName = uiState.currentServerName,
                rttMs = uiState.currentRtt,
                traffic = traffic
            )

            Spacer(Modifier.height(24.dp))
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

    UpdateDialog(
        state = updateState,
        onDownload = { updateViewModel.download() },
        onInstall = { updateViewModel.install() },
        onDismiss = { updateViewModel.dismiss() }
    )
}
