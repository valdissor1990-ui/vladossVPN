package com.vpnproxy.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Inter / Google Sans не подключены как ассеты — используем системный + моноширинный.
private val SansFamily = FontFamily.SansSerif
private val MonoFamily = FontFamily.Monospace

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 56.sp,
        letterSpacing = (-1).sp
    ),
    titleLarge = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp
    ),
    titleMedium = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    labelLarge = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.5.sp
    ),
    labelMedium = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    ),
    labelSmall = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp
    )
)
