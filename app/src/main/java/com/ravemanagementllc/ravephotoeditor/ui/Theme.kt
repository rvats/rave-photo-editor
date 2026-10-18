package com.ravemanagementllc.ravephotoeditor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RaveColors = darkColorScheme(
    primary = Color(0xFFF472B6),
    onPrimary = Color(0xFF2B0A1E),
    primaryContainer = Color(0xFF5B1F45),
    onPrimaryContainer = Color(0xFFFCE7F3),
    secondary = Color(0xFFA78BFA),
    onSecondary = Color(0xFF1E1036),
    secondaryContainer = Color(0xFF2E2350),
    onSecondaryContainer = Color(0xFFEDE9FE),
    background = Color(0xFF0E0F14),
    onBackground = Color(0xFFECEDF2),
    surface = Color(0xFF171922),
    onSurface = Color(0xFFECEDF2),
    surfaceVariant = Color(0xFF232634),
    onSurfaceVariant = Color(0xFFA9ADBD),
    outline = Color(0xFF3A3E4D)
)

@Composable
fun RaveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RaveColors, content = content)
}
