package com.vpnproxy.ui.home

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Объёмная неоновая кнопка подключения.
 */
@Composable
fun NeonConnectButton(
    isConnected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorCore = if (isConnected) Color(0xFFFF1744) else Color(0xFF00E676)
    val colorMid = if (isConnected) Color(0xFFC62828) else Color(0xFF00A85A)
    val colorDark = if (isConnected) Color(0xFF6A0000) else Color(0xFF004D2C)
    val colorGlow = if (isConnected) Color(0xFFFF5252) else Color(0xFF69F0AE)

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val infinite = rememberInfiniteTransition(label = "neon_pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val glowAlpha = if (isConnected) pulse * 1.0f else pulse * 0.55f

    val pressedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "press"
    )

    val buttonSize = 200.dp
    val iconSize = if (isConnected) 56.dp else 64.dp

    Box(
        modifier = modifier
            .size(buttonSize + 60.dp)
            .scale(pressedScale),
        contentAlignment = Alignment.Center
    ) {
        // Внешнее неоновое свечение
        Box(
            modifier = Modifier
                .size(buttonSize + 40.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            colorGlow.copy(alpha = 0.55f * glowAlpha),
                            colorGlow.copy(alpha = 0.25f * glowAlpha),
                            Color.Transparent
                        ),
                        radius = (buttonSize.value + 40f) * 1.4f
                    )
                )
        )

        // Второе кольцо свечения
        Box(
            modifier = Modifier
                .size(buttonSize + 20.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            colorGlow.copy(alpha = 0.7f * glowAlpha),
                            Color.Transparent
                        ),
                        radius = (buttonSize.value + 20f) / 1.2f
                    )
                )
        )

        // Основной круг (3D-градиент + тень)
        Box(
            modifier = Modifier
                .size(buttonSize)
                .shadow(
                    elevation = 24.dp,
                    shape = CircleShape,
                    ambientColor = colorCore.copy(alpha = 0.8f),
                    spotColor = colorCore.copy(alpha = 0.9f)
                )
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            colorMid,
                            colorCore,
                            colorDark
                        ),
                        radius = buttonSize.value / 1.8f
                    )
                )
        )

        // Верхний блик
        Box(
            modifier = Modifier
                .size(buttonSize)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.35f),
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent
                        ),
                        radius = buttonSize.value / 2.5f,
                        center = Offset(
                            x = buttonSize.value * 0.35f,
                            y = buttonSize.value * 0.3f
                        )
                    )
                )
        )

        // Контент + клик
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .size(buttonSize)
                .clip(CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                )
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Default.PowerSettingsNew
                else Icons.Default.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .size(iconSize)
                    .graphicsLayer {
                        shadowElevation = 8f
                        shape = CircleShape
                        clip = false
                        ambientShadowColor = colorCore
                        spotShadowColor = colorCore
                    }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (isConnected) "ОТКЛЮЧИТЬ" else "ПОДКЛЮЧИТЬ",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                letterSpacing = 1.2.sp
            )
        }
    }
}
