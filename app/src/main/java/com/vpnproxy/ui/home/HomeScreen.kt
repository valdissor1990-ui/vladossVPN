package com.vpnproxy.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vpnproxy.ui.theme.ConnectedGreen
import com.vpnproxy.ui.theme.ConnectingBlue
import com.vpnproxy.ui.theme.DisconnectedRed
import com.vpnproxy.ui.theme.WarningYellow
import com.vpnproxy.ui.update.UpdateDialog
import com.vpnproxy.ui.update.UpdateViewModel

@OptIn(ExperimentalMaterial3Api::class)
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
    val googleRtt by viewModel.googleRtt.collectAsStateWithLifecycle()
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        updateViewModel.check()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VPNProxy") },
                actions = {
                    IconButton(onClick = onNavigateToSubscriptions) {
                        Icon(Icons.Default.Cloud, "Подписки")
                    }
                    IconButton(onClick = onNavigateToDebug) {
                        Icon(Icons.Default.BugReport, "Журнал")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, "Настройки")
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    ConnectionStatusCircle(state = uiState.connectionState)

                    Spacer(Modifier.height(28.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            when {
                                uiState.connectionState == ConnectionUiState.CONNECTED ||
                                    uiState.connectionState == ConnectionUiState.UNSTABLE -> {
                                    Text(
                                        text = "VPN RTT: ${uiState.currentRtt} ms",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = uiState.currentServerName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                                        maxLines = 1
                                    )
                                }
                                else -> {
                                    Text(
                                        text = if (googleRtt != null) "Ping Google: $googleRtt ms"
                                        else "Ping Google: —",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = when {
                                            googleRtt == null ->
                                                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                                            googleRtt!! < 100 -> Color(0xFF4CAF50)
                                            googleRtt!! < 300 -> Color(0xFFFFC107)
                                            else -> Color(0xFFF44336)
                                        }
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = when {
                                            uiState.serverCount > 0 -> "Серверов: ${uiState.serverCount}"
                                            else -> "Добавьте подписку"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(40.dp))

                    NeonConnectButton(
                        isConnected = uiState.connectionState == ConnectionUiState.CONNECTED ||
                            uiState.connectionState == ConnectionUiState.UNSTABLE,
                        enabled = !uiState.isBusy,
                        onClick = { viewModel.toggleConnection() }
                    )

                    Spacer(Modifier.height(20.dp))

                    OutlinedButton(
                        onClick = { viewModel.switchServer() },
                        modifier = Modifier
                            .width(200.dp)
                            .height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        enabled = !uiState.isBusy &&
                            (uiState.connectionState == ConnectionUiState.CONNECTED ||
                                uiState.connectionState == ConnectionUiState.UNSTABLE)
                    ) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("СМЕНИТЬ", fontWeight = FontWeight.Medium)
                    }
                }
            }

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
        Icon(icon, null, modifier = Modifier.size(64.dp), tint = color)
    }
}
