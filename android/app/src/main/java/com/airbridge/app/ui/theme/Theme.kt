package com.airbridge.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SimpleLightColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
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
