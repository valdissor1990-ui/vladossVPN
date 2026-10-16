package com.vpnproxy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.vpnproxy.ui.home.ConnectionUiState
import com.vpnproxy.ui.theme.*

@Composable
fun GlowPowerButton(
    state: ConnectionUiState,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "press"
    )

    val targetColor = when (state) {
        ConnectionUiState.CONNECTED, ConnectionUiState.UNSTABLE -> ConnectedGreen
        ConnectionUiState.CONNECTING -> GlowBlue
        ConnectionUiState.ERROR -> DisconnectRed
        ConnectionUiState.DISCONNECTED -> OnDarkSecondary
    }
    val ringColor by animateColorAsState(targetColor, tween(350, easing = EaseOutCubic), label = "ring")

    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.7f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val isPulsing = state == ConnectionUiState.CONNECTING
    val glowFactor = if (isPulsing) pulse else 1f

    val buttonSize = 240.dp

    Box(
        modifier = modifier
            .size(buttonSize)
            .scale(pressedScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val glowStroke = 8.dp.toPx()
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)

            val broken = state == ConnectionUiState.DISCONNECTED || state == ConnectionUiState.ERROR
            val startAngle = if (broken) 30f else 0f
            val sweep = if (broken) 300f else 360f

            listOf(
                glowStroke * 3 to 0.08f,
                glowStroke * 2 to 0.15f,
                glowStroke to 0.28f
            ).forEach { (w, a) ->
                drawArc(
                    color = ringColor.copy(alpha = a * glowFactor),
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = w, cap = StrokeCap.Round)
                )
            }

            drawArc(
                color = ringColor,
                startAngle = startAngle,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }

        Surface(
            shape = CircleShape,
            color = CosmosSurface,
            modifier = Modifier.size(180.dp),
            shadowElevation = if (state == ConnectionUiState.CONNECTED || state == ConnectionUiState.UNSTABLE) 24.dp else 8.dp,
            tonalElevation = 0.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                when (state) {
                    ConnectionUiState.CONNECTED, ConnectionUiState.UNSTABLE ->
                        Icon(Icons.Default.Shield, null,
                            tint = ConnectedGreen, modifier = Modifier.size(72.dp))
                    ConnectionUiState.CONNECTING ->
                        Icon(Icons.Default.Shield, null,
                            tint = GlowBlue.copy(alpha = pulse),
                            modifier = Modifier.size(72.dp))
                    else ->
                        Icon(Icons.Default.Shield, null,
                            tint = OnDarkMuted, modifier = Modifier.size(72.dp))
                }
            }
        }
    }
}
