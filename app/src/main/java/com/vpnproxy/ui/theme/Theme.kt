package com.vpnproxy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Светлая тема главного клиента. Тёмная космическая схема убрана с экрана,
 * чтобы интерфейс совпадал с референсом.
 */
private val ClientLightScheme = lightColorScheme(
    primary = HappSelected,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8ECFF),
    onPrimaryContainer = HappInk,
    secondary = HappMuted,
    onSecondary = Color.White,
    tertiary = ConnectedGreen,
    onTertiary = Color.White,
    background = HappBg,
    onBackground = HappInk,
    surface = Color.White,
    onSurface = HappInk,
    surfaceVariant = HappRemarks,
    onSurfaceVariant = HappMuted,
    outline = HappLine,
    error = DisconnectRed,
    onError = Color.White
)

@Composable
fun VPNProxyTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ClientLightScheme,
        typography = Typography,
        content = content
    )
}
