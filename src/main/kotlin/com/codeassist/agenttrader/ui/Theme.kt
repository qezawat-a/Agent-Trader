package com.codeassist.agenttrader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.isSystemInDarkTheme

/** Trading-floor color scheme shared by light and dark themes. */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF06251A),
    primaryContainer = Color(0xFF123A2B),
    onPrimaryContainer = Color(0xFFD1FAE5),
    secondary = Color(0xFF60A5FA),
    onSecondary = Color(0xFF0A1B33),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF2A1D00),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE5EDF8),
    surface = Color(0xFF101A2D),
    onSurface = Color(0xFFE5EDF8),
    surfaceVariant = Color(0xFF1B2A44),
    onSurfaceVariant = Color(0xFFB9C7DD),
    error = Color(0xFFF87171),
    onError = Color(0xFF2A0505),
    outline = Color(0xFF33415C),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF001B0D),
    primaryContainer = Color(0xFF123A2B),
    onPrimaryContainer = Color(0xFF064E34),
    secondary = Color(0xFF2563EB),
    onSecondary = Color(0xFF0A1B33),
    tertiary = Color(0xFFD97706),
    onTertiary = Color(0xFF451A00),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0B1220),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B1220),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF33415C),
    error = Color(0xFFDC2626),
    onError = Color(0xFF450A0A),
    outline = Color(0xFF94A3B8),
)

/** Compose theme for Agent Trader. Follows the system dark/light setting by default. */
@Composable
fun TraderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        content = content,
    )
}
