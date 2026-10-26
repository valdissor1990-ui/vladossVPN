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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

                if (uiState.connectionState == ConnectionUiState.ERROR) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF5C1010).copy(alpha = 0.92f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFFF8A80)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Не удалось подключиться. Проверьте сеть или смените сервер.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFFFCDD2),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    Log.i("HomeScreen", "HomeScreen: User pressed Retry button")
                                    viewModel.retryConnect()
                                },
                                enabled = !uiState.isBusy,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFE53935),
                                    contentColor = Color.White
                                )
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

                if (uiState.isScanningServers) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Поиск доступных серверов...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

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
                    serverRtt = uiState.currentRtt,
                    traffic = traffic
                )

                Spacer(Modifier.height(24.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showServers = true },
                    colors = CardDefaults.cardColors(
                        containerColor = CosmosSurface.copy(alpha = 0.9f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🌐", fontSize = 40.sp)
                        Spacer(Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = uiState.currentServerName.ifBlank { "Не выбран" },
                                style = MaterialTheme.typography.titleMedium,
                                color = OnDark
                            )
                            Text(
                                text = "Нажмите, чтобы сменить",
                                style = MaterialTheme.typography.labelMedium,
                                color = OnDarkSecondary
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
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

    UpdateDialog(
        state = updateState,
        onDownload = { updateViewModel.download() },
        onInstall = { updateViewModel.install() },
        onDismiss = { updateViewModel.dismiss() }
    )
}
