package com.vpnproxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpnproxy.core.tunnel.TrafficStatsCollector
import com.vpnproxy.ui.home.ConnectionUiState
import com.vpnproxy.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun ConnectionStatsCard(
    state: ConnectionUiState,
    sessionStartedAt: Long?,
    serverName: String,
    serverRtt: Long,
    traffic: TrafficStatsCollector.TrafficSnapshot,
    modifier: Modifier = Modifier
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sessionStartedAt, state) {
        while (state == ConnectionUiState.CONNECTED || state == ConnectionUiState.UNSTABLE) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val sessionText = if (sessionStartedAt != null && sessionStartedAt > 0) {
        val elapsed = (now - sessionStartedAt).coerceAtLeast(0L) / 1000
        String.format(Locale.US, "%02d:%02d:%02d",
            elapsed / 3600, (elapsed % 3600) / 60, elapsed % 60)
    } else "--:--:--"

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = sessionText,
            style = MaterialTheme.typography.displayLarge,
            color = if (state == ConnectionUiState.CONNECTED) ConnectedGreen else OnDarkSecondary
        )

        Spacer(Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = serverName.ifBlank { "—" },
                style = MaterialTheme.typography.titleMedium,
                color = OnDark
            )
            if (serverRtt in 1..9999 &&
                (state == ConnectionUiState.CONNECTED || state == ConnectionUiState.UNSTABLE)
            ) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${serverRtt}ms",
                    style = MaterialTheme.typography.labelLarge,
                    color = when {
                        serverRtt < 100 -> ConnectedGreen
                        serverRtt < 200 -> WarningYellow
                        else -> DisconnectRed
                    }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatPill("↓", formatSpeed(traffic.downloadMbps), GlowBlue)
            StatPill("↑", formatSpeed(traffic.uploadMbps), ConnectedGreen)
        }
    }
}

@Composable
private fun StatPill(arrow: String, value: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(CosmosSurfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(arrow, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.width(4.dp))
        Text(
            text = value,
            color = OnDark,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
    }
}

private fun formatSpeed(mbps: Float): String = when {
    mbps < 0.001f -> "0 Kb/s"
    mbps < 1f -> String.format(Locale.US, "%.0f Kb/s", mbps * 1000)
    else -> String.format(Locale.US, "%.1f Mb/s", mbps)
}
