package com.vpnproxy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Тёмная тема — космическая, без Material You.
 * Светлая — минимальная, на случай системного переключения.
 */
private val CosmosDarkScheme = darkColorScheme(
    primary = GlowBlue,
    onPrimary = Color.White,
    primaryContainer = CosmosSurfaceVariant,
    onPrimaryContainer = OnDark,
    secondary = GlowBlueBright,
    onSecondary = Color.White,
    tertiary = ConnectedGreen,
    onTertiary = Color.White,
    background = DeepSpaceBlue,
    onBackground = OnDark,
    surface = CosmosSurface,
    onSurface = OnDark,
    surfaceVariant = CosmosSurfaceVariant,
    onSurfaceVariant = OnDarkSecondary,
    error = DisconnectRed,
    onError = Color.White
)

private val FallbackLightScheme = lightColorScheme()

@Composable
fun VPNProxyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // dynamicColor сохранён в сигнатуре ради совместимости вызовов; намеренно не используется
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) CosmosDarkScheme else FallbackLightScheme,
        typography = Typography,
        content = content
    )
}
