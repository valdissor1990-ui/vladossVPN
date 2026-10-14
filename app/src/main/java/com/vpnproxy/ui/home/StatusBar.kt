package com.vpnproxy.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpnproxy.ui.theme.StatusConnected
import com.vpnproxy.ui.theme.StatusConnecting
import com.vpnproxy.ui.theme.StatusDisconnected
import com.vpnproxy.ui.theme.StatusWarning
import java.util.Locale

@Composable
fun StatusBar(
    state: ConnectionUiState,
    downloadMbps: Float,
    uploadMbps: Float,
    totalRx: Long,
    totalTx: Long,
    sessionRx: Long,
    sessionTx: Long,
    modifier: Modifier = Modifier
) {
    val targetColor = when (state) {
        ConnectionUiState.CONNECTED -> StatusConnected
        ConnectionUiState.UNSTABLE -> StatusWarning
        ConnectionUiState.CONNECTING -> StatusConnecting
        ConnectionUiState.DISCONNECTED, ConnectionUiState.ERROR -> StatusDisconnected
    }

    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(600),
        label = "status_color"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
        color = animatedColor.copy(alpha = pulseAlpha),
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "↓ ${formatSpeed(downloadMbps)}",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.width(20.dp))
                Text(
                    text = "↑ ${formatSpeed(uploadMbps)}",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = state.label(),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "RX ${formatBytes(totalRx)}  TX ${formatBytes(totalTx)}",
                    color = Color.White.copy(alpha = 0.85f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
                Spacer(modifier.weight(1f))
                Text(
                    text = "Сессия: ${formatBytes(sessionRx)} / ${formatBytes(sessionTx)}",
                    color = Color.White.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/** Форматирование скорости: Кбит/с или Мбит/с. */
private fun formatSpeed(mbps: Float): String {
    return when {
        mbps < 0.001f -> "0 Кбит/с"
        mbps < 1f -> "${String.format(Locale.US, "%.0f", mbps * 1000)} Кбит/с"
        else -> "${String.format(Locale.US, "%.1f", mbps)} Мбит/с"
    }
}

/** Форматирование байтов. */
private fun formatBytes(bytes: Long): String {
    val loc = Locale.US
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${String.format(loc, "%.1f", bytes / 1024.0)} KB"
        bytes < 1024L * 1024 * 1024 -> "${String.format(loc, "%.1f", bytes / (1024.0 * 1024))} MB"
        else -> "${String.format(loc, "%.2f", bytes / (1024.0 * 1024 * 1024))} GB"
    }
}
