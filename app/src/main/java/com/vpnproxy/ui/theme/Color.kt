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
val ConnectedGreen = Color(0xFF1FA971)
val DisconnectRed = Color(0xFFE23B3B)
val WarningYellow = Color(0xFFE0A100)
val ConnectingBlue = Color(0xFF64B5F6)
val QualityBlack = Color(0xFF161616)

// Светлый главный экран (как в референсе клиента)
val HappBg = Color(0xFFF3F4F6)
val HappInk = Color(0xFF2C3344)
val HappMuted = Color(0xFF8E96A8)
val HappLine = Color(0xFFE6E8EE)
val HappRemarks = Color(0xFFF4F6FB)
val HappSelected = Color(0xFF4C6FFF)

// Текст
val OnDark = Color(0xFFE6EEFA)
val OnDarkSecondary = Color(0xFF94A7C9)
val OnDarkMuted = Color(0xFF5E7191)

// Legacy aliases (старые экраны / StatusBar)
@Deprecated("Use DisconnectRed")
val DisconnectedRed = DisconnectRed

@Deprecated("Use GlowBlue")
val Primary = GlowBlue
val OnPrimary = Color.White

@Deprecated("Use CosmosSurface")
val SurfaceDark = CosmosSurface
@Deprecated("Use OnDark")
val SurfaceLight = Color(0xFFF5F5F5)

// Status bar variants (legacy)
val StatusConnected = Color(0xFF1B5E20)
val StatusWarning = Color(0xFFF57F17)
val StatusConnecting = Color(0xFF0D47A1)
val StatusDisconnected = Color(0xFFB71C1C)
