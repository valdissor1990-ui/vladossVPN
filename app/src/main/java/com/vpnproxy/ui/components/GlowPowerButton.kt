package com.vpnproxy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vpnproxy.ui.home.ConnectionUiState
import com.vpnproxy.ui.home.LinkQuality
import com.vpnproxy.ui.theme.HappInk
import com.vpnproxy.ui.theme.HappMuted
import com.vpnproxy.ui.theme.QualityBlack
import kotlinx.coroutines.delay

@Composable
fun GlowPowerButton(
    state: ConnectionUiState,
    quality: LinkQuality,
    sessionStartedAt: Long?,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isScanning: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "press"
    )
    val accent = when (quality) {
        LinkQuality.GOOD -> Color(0xFF1FA971)
        LinkQuality.MEDIUM -> Color(0xFFE0A100)
        LinkQuality.BAD -> Color(0xFFE23B3B)
        LinkQuality.DEAD -> QualityBlack
    }
    val ringColor by animateColorAsState(accent, tween(420), label = "quality")
    val connected = state.hasTunnel()
    val busy = state == ConnectionUiState.CONNECTING || state == ConnectionUiState.VERIFYING

    val infinite = rememberInfiniteTransition(label = "power")
    val pulse by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = if (connected) 1f else 0.35f,
        animationSpec = if (connected) {
            infiniteRepeatable(
                animation = tween(1100),
                repeatMode = RepeatMode.Reverse
            )
        } else {
            tween(0)
        },
        label = "pulse"
    )
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = if (busy) 360f else 0f,
        animationSpec = if (busy) {
            infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        } else {
            tween(0)
        },
        label = "spin"
    )

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sessionStartedAt, connected) {
        if (!connected || sessionStartedAt == null) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = if (connected && sessionStartedAt != null) {
        formatElapsed((now - sessionStartedAt).coerceAtLeast(0L))
    } else {
        "00:00:00"
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(228.dp)
            .scale(pressedScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxR = size.minDimension / 2f
            if (connected) {
                val rings = listOf(0.98f to 0.16f, 0.86f to 0.28f, 0.74f to 0.5f)
                rings.forEachIndexed { index, (radiusFactor, alpha) ->
                    val breathe = 1f + (pulse - 0.35f) * (0.035f + index * 0.012f)
                    drawCircle(
                        color = ringColor.copy(alpha = alpha * (0.55f + pulse * 0.45f)),
                        radius = maxR * radiusFactor * breathe,
                        center = center,
                        style = Stroke(width = (1.4f + index) * density)
                    )
                }
            } else {
                drawCircle(
                    color = Color(0xFFD5D8E0),
                    radius = maxR * 0.96f,
                    center = center,
                    style = Stroke(width = 1.2f * density)
                )
                drawCircle(
                    color = Color(0xFFE4E6EC),
                    radius = maxR * 0.82f,
                    center = center,
                    style = Stroke(width = 1.1f * density)
                )
            }
            if (busy) {
                drawArc(
                    color = ringColor.copy(alpha = 0.9f),
                    startAngle = spin,
                    sweepAngle = 86f,
                    useCenter = false,
                    topLeft = Offset(size.width * 0.16f, size.height * 0.16f),
                    size = Size(size.width * 0.68f, size.height * 0.68f),
                    style = Stroke(width = 3.2f * density, cap = StrokeCap.Round)
                )
            }
        }

        Box(
            modifier = Modifier
                .size(158.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Canvas(Modifier.size(34.dp)) {
                    val stroke = 2.6f * density
                    val color = ringColor
                    drawArc(
                        color = color,
                        startAngle = 130f,
                        sweepAngle = 280f,
                        useCenter = false,
                        topLeft = Offset(size.width * 0.16f, size.height * 0.08f),
                        size = Size(size.width * 0.68f, size.height * 0.68f),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                    drawLine(
                        color = color,
                        start = Offset(size.width / 2f, size.height * 0.06f),
                        end = Offset(size.width / 2f, size.height * 0.48f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
                Text(
                    text = if (isScanning && !connected) "ОСТАНОВИТЬ" else state.label(),
                    color = if (connected) ringColor else HappMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = elapsed,
                    color = HappInk,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp
                )
            }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val total = ms / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return "%02d:%02d:%02d".format(h, m, s)
}
