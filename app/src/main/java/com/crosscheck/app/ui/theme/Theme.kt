package com.crosscheck.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Verdict colours (shared by the Log screen now and the Verify/Receipt screens later). */
object VerdictColors {
    val Match = Color(0xFF1B5E20)
    val Likely = Color(0xFFF57F17)
    val NoMatch = Color(0xFFB71C1C)
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1B5E20),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF0B3D12),
    secondary = Color(0xFF37474F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFD8DC),
    onSecondaryContainer = Color(0xFF102027),
    error = Color(0xFFB71C1C),
    onError = Color.White,
    errorContainer = Color(0xFFFFCDD2),
    onErrorContainer = Color(0xFF5F0A0A),
    background = Color(0xFFFAFAF7),
    onBackground = Color(0xFF1A1C19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C19),
    surfaceVariant = Color(0xFFE6E9E2),
    onSurfaceVariant = Color(0xFF43483F),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF00390A),
    primaryContainer = Color(0xFF1B5E20),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFFB0BEC5),
    onSecondary = Color(0xFF1C313A),
    secondaryContainer = Color(0xFF37474F),
    onSecondaryContainer = Color(0xFFCFD8DC),
    error = Color(0xFFEF9A9A),
    onError = Color(0xFF5F0A0A),
    errorContainer = Color(0xFF8E1B1B),
    onErrorContainer = Color(0xFFFFCDD2),
    background = Color(0xFF111411),
    onBackground = Color(0xFFE2E3DD),
    surface = Color(0xFF191C19),
    onSurface = Color(0xFFE2E3DD),
    surfaceVariant = Color(0xFF43483F),
    onSurfaceVariant = Color(0xFFC3C8BC),
)

@Composable
fun CrossCheckTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
