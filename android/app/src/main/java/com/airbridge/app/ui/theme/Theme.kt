package com.airbridge.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = BridgeClay,
    secondary = BridgeTeal,
    background = BridgeSand,
    surface = BridgeSand,
    onPrimary = BridgeSand,
    onSecondary = BridgeSand,
    onBackground = BridgeInk,
    onSurface = BridgeInk,
)

private val DarkColors = darkColorScheme(
    primary = BridgeFoam,
    secondary = BridgeClay,
    background = BridgeNight,
    surface = BridgeInk,
    onPrimary = BridgeNight,
    onSecondary = BridgeSand,
    onBackground = BridgeSand,
    onSurface = BridgeSand,
)

@Composable
fun AirBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AirBridgeTypography,
        content = content,
    )
}
