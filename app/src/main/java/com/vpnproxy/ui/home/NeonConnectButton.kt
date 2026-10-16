package com.vpnproxy.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

/**
 * ★ Круглая кнопка питания с вращающимся градиентным кольцом.
 *
 * Дизайн (по референсу):
 *   - Белый круг с мягкой тенью
 *   - Иконка PowerSettingsNew в центре (без текста)
 *   - Тонкое вращающееся градиентное кольцо
 *   - Красное свечение когда VPN выключен
 *   - Зелёное свечение когда VPN включён
 */
@Composable
fun NeonConnectButton(
    isConnected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ringStart = if (isConnected) Color(0xFF4CAF50) else Color(0xFFEF5350)
    val ringEnd   = if (isConnected) Color(0xFFA5D6A7) else Color(0xFFFFCDD2)

    val animatedRingStart by animateColorAsState(ringStart, tween(500), label = "rs")
    val animatedRingEnd   by animateColorAsState(ringEnd,   tween(500), label = "re")

    val iconColor = if (isConnected) Color(0xFF2E7D32) else Color(0xFF757575)

    val infinite = rememberInfiniteTransition(label = "ring")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rot"
    )
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "press"
    )

    val size = 220.dp
    val innerSize = 170.dp
    val ringStroke = 3.dp

    Box(
        modifier = modifier
            .size(size)
            .scale(pressedScale),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = (size.toPx() - ringStroke.toPx()) / 2f
            val center = Offset(size.toPx() / 2f, size.toPx() / 2f)

            rotate(rotation, pivot = center) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            animatedRingStart.copy(alpha = 0f),
                            animatedRingStart.copy(alpha = 0.9f * pulseAlpha),
                            animatedRingEnd.copy(alpha = 1f * pulseAlpha),
                            animatedRingEnd.copy(alpha = 0.9f * pulseAlpha),
                            animatedRingStart.copy(alpha = 0f)
                        ),
                        center = center
                    ),
                    radius = radius,
                    center = center,
                    style = Stroke(width = ringStroke.toPx())
                )
            }
        }

        Surface(
            modifier = Modifier
                .size(innerSize)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                ),
            shape = CircleShape,
            color = Color.White,
            shadowElevation = 12.dp,
            tonalElevation = 0.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = if (isConnected) "Отключить" else "Подключить",
                    tint = iconColor,
                    modifier = Modifier.size(56.dp)
                )
            }
        }
    }
}
