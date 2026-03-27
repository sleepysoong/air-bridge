package com.airbridge.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SimpleLightColorScheme = lightColorScheme(
    primary = Color(0xFF5A8CFF),
    onPrimary = Color.White,
    secondary = Color(0xFF7DE7D4),
    onSecondary = Color(0xFF122033),
    background = Color(0xFFF9FBFF),
    onBackground = Color(0xFF132033),
    surface = Color(0xFFF4F8FF),
    onSurface = Color(0xFF132033),
    surfaceVariant = Color(0xFFE8F0FF),
    onSurfaceVariant = Color(0xFF5D6A80),
)

@Composable
fun AirBridgeTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = SimpleLightColorScheme,
        content = content,
    )
}
