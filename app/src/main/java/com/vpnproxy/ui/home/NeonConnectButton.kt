package com.vpnproxy.ui.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ★ Хайтек-кнопка подключения.
 *
 * Стиль: угловатая форма, тонкая неоновая рамка, статичное свечение по периметру,
 * двухцветный градиент при активности, моноширинный текст.
 * Без «пузырящейся» сферы — строгая геометрия, как на приборной панели.
 */
@Composable
fun NeonConnectButton(
    isConnected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = if (isConnected) Color(0xFFFF3B30) else Color(0xFF00E5FF)
    val accentDim = accent.copy(alpha = 0.35f)
    val bg = Color(0xFF0A0E14)

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Пульсация свечения — мягкая, 2 секунды
    val infinite = rememberInfiniteTransition(label = "ht")
    val pulse by infinite.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val pressedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "press"
    )

    val width = 220.dp
    val height = 80.dp

    Box(
        modifier = modifier
            .size(width, height)
            .scale(pressedScale)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(1.dp, accentDim, RoundedCornerShape(4.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Неоновое свечение по периметру через Canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 2.dp.toPx()
            drawRoundRect(
                color = accent.copy(alpha = 0.15f * pulse),
                cornerRadius = CornerRadius(4.dp.toPx()),
                style = Stroke(width = stroke * 4)
            )
            drawRoundRect(
                color = accent.copy(alpha = 0.5f * pulse),
                cornerRadius = CornerRadius(4.dp.toPx()),
                style = Stroke(width = stroke)
            )
        }

        // Градиентная подсветка слева (хайтек-акцент)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(4.dp)
                .align(Alignment.CenterStart)
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = 0f), accent, accent.copy(alpha = 0f))
                    )
                )
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Default.PowerSettingsNew
                              else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (isConnected) "ОТКЛЮЧИТЬ" else "ПОДКЛЮЧИТЬ",
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 2.sp
            )
        }
    }
}
