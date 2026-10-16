package com.vpnproxy.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    // Проверка обновлений при первом входе — один раз за жизнь ViewModel.
    LaunchedEffect(Unit) {
        updateViewModel.checkOnce()
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
                                    if (uiState.serverCount > 0) {
                                        Text(
                                            text = "Серверов: ${uiState.serverCount}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                                        )
                                    } else {
                                        Surface(
                                            onClick = onNavigateToSubscriptions,
                                            shape = RoundedCornerShape(16.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(
                                                    horizontal = 12.dp,
                                                    vertical = 6.dp
                                                ),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.Add,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(
                                                    "Добавить подписку",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(40.dp))

                    if (uiState.connectionState == ConnectionUiState.CONNECTING) {
                        OutlinedButton(
                            onClick = { viewModel.forceDisconnect() },
                            modifier = Modifier
                                .width(220.dp)
                                .height(80.dp),
                            shape = RoundedCornerShape(4.dp),
                            border = ButtonDefaults.outlinedButtonBorder.copy(
                                brush = SolidColor(Color(0xFFFF3B30))
                            )
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = null,
                                tint = Color(0xFFFF3B30)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "ОТМЕНИТЬ",
                                color = Color(0xFFFF3B30),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        NeonConnectButton(
                            isConnected = uiState.connectionState == ConnectionUiState.CONNECTED ||
                                uiState.connectionState == ConnectionUiState.UNSTABLE,
                            enabled = !uiState.isBusy,
                            onClick = { viewModel.toggleConnection() }
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = { viewModel.switchServer() },
                        modifier = Modifier
                            .width(160.dp)
                            .height(40.dp),
                        shape = RoundedCornerShape(4.dp),
                        enabled = !uiState.isBusy &&
                            (uiState.connectionState == ConnectionUiState.CONNECTED ||
                                uiState.connectionState == ConnectionUiState.UNSTABLE),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = SolidColor(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            )
                        )
                    ) {
                        Icon(
                            Icons.Default.SwapHoriz,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "ДРУГОЙ СЕРВЕР",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
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
