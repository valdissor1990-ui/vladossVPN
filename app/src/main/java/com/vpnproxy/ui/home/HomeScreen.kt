package com.vpnproxy.ui.home

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnproxy.ui.theme.*
import com.vpnproxy.ui.update.UpdateDialog
import com.vpnproxy.ui.update.UpdateViewModel
import com.vpnproxy.update.UpdateManager

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel(),
    onNavigateToSubscriptions: () -> Unit = {},
    onNavigateToDebug: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val traffic by viewModel.traffic.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()

    // Автопроверка обновлений при каждом открытии главного экрана
    LaunchedEffect(Unit) {
        updateViewModel.check()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VPNProxy") },
                actions = {
                    // Кнопка ручной проверки обновлений
                    IconButton(
                        onClick = { updateViewModel.check() },
                        enabled = updateState !is UpdateManager.State.Checking &&
                                  updateState !is UpdateManager.State.Downloading
                    ) {
                        if (updateState is UpdateManager.State.Checking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.SystemUpdate, contentDescription = "Проверить обновления")
                        }
                    }
                    IconButton(onClick = onNavigateToSubscriptions) {
                        Icon(Icons.Default.Cloud, contentDescription = "Подписки")
                    }
                    IconButton(onClick = onNavigateToDebug) {
                        Icon(Icons.Default.BugReport, contentDescription = "Журнал")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Основной контент
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Круг состояния
                    ConnectionStatusCircle(state = uiState.connectionState)

                    Spacer(Modifier.height(24.dp))

                    // RTT и сервер
                    if (uiState.connectionState == ConnectionUiState.CONNECTED) {
                        Text(
                            text = "RTT: ${uiState.currentRtt} ms",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = uiState.currentServerName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                        )
                    }

                    Spacer(Modifier.height(40.dp))

                    // Кнопки
                    Button(
                        onClick = { viewModel.toggleConnection() },
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(56.dp),
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.connectionState == ConnectionUiState.CONNECTED)
                                StatusDisconnected else ConnectedGreen
                        )
                    ) {
                        Icon(
                            if (uiState.connectionState == ConnectionUiState.CONNECTED)
                                Icons.Default.PowerSettingsNew else Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (uiState.connectionState == ConnectionUiState.CONNECTED)
                                "ОТКЛЮЧИТЬ" else "ПОДКЛЮЧИТЬ",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    OutlinedButton(
                        onClick = { viewModel.switchServer() },
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        enabled = uiState.connectionState == ConnectionUiState.CONNECTED
                    ) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("СМЕНИТЬ")
                    }

                    Spacer(Modifier.height(12.dp))

                    // Кнопка проверки обновлений (текстовая, для удобства)
                    TextButton(
                        onClick = { updateViewModel.check() },
                        enabled = updateState !is UpdateManager.State.Checking &&
                                  updateState !is UpdateManager.State.Downloading
                    ) {
                        Icon(
                            Icons.Default.SystemUpdate,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when (updateState) {
                                is UpdateManager.State.Checking -> "Проверка…"
                                is UpdateManager.State.UpdateAvailable -> "Доступно обновление!"
                                is UpdateManager.State.UpToDate -> "Обновлений нет"
                                else -> "Проверить обновления"
                            }
                        )
                    }
                }
            }

            // ★ Цветная строка состояния внизу
            StatusBar(
                state = uiState.connectionState,
                downloadMbps = traffic.downloadMbps,
                uploadMbps = traffic.uploadMbps,
                totalRx = traffic.totalRx,
                totalTx = traffic.totalTx,
                sessionRx = traffic.sessionRx,
                sessionTx = traffic.sessionTx
            )
        }
    }

    // Диалог обновления
    UpdateDialog(
        state = updateState,
        onDownload = { updateViewModel.download() },
        onInstall = { updateViewModel.install() },
        onDismiss = { updateViewModel.dismiss() }
    )
}

@Composable
private fun ConnectionStatusCircle(state: ConnectionUiState) {
    val (color, icon) = when (state) {
        ConnectionUiState.CONNECTED -> ConnectedGreen to Icons.Default.CheckCircle
        ConnectionUiState.UNSTABLE -> WarningYellow to Icons.Default.Warning
        ConnectionUiState.CONNECTING -> ConnectingBlue to Icons.Default.Sync
        ConnectionUiState.DISCONNECTED -> DisconnectedRed to Icons.Default.PowerSettingsNew
        ConnectionUiState.ERROR -> DisconnectedRed to Icons.Default.Error
    }

    Box(
        modifier = Modifier
            .size(160.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(color.copy(alpha = 0.3f), color.copy(alpha = 0.1f))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = color
        )
    }
}
