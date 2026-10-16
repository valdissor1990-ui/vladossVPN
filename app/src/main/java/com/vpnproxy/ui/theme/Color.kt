package com.vpnproxy.ui.theme

import androidx.compose.ui.graphics.Color

// Фон и поверхности
val DeepSpaceBlue = Color(0xFF0B1B33)
val DeepSpaceVoid = Color(0xFF050D1C)
val CosmosSurface = Color(0xFF142647)
val CosmosSurfaceVariant = Color(0xFF1C2F52)

// Акценты
val GlowBlue = Color(0xFF4DA3FF)
val GlowBlueBright = Color(0xFF8DC6FF)

// Состояния
val ConnectedGreen = Color(0xFF3DDC84)
val DisconnectRed = Color(0xFFFF5A5F)
val WarningYellow = Color(0xFFFFC107)
val ConnectingBlue = Color(0xFF64B5F6)

// Текст
val OnDark = Color(0xFFE6EEFA)
val OnDarkSecondary = Color(0xFF94A7C9)
val OnDarkMuted = Color(0xFF5E7191)

// Legacy aliases (StatusBar / HomeScreen до полной замены)
@Deprecated("Use DisconnectRed")
val DisconnectedRed = DisconnectRed

// Status bar variants (legacy)
val StatusConnected = Color(0xFF1B5E20)
val StatusWarning = Color(0xFFF57F17)
val StatusConnecting = Color(0xFF0D47A1)
val StatusDisconnected = Color(0xFFB71C1C)
