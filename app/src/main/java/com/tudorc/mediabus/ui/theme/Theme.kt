package com.tudorc.mediabus.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = NightPrimary,
    onPrimary = NightOnPrimary,
    primaryContainer = NightPrimaryContainer,
    onPrimaryContainer = NightOnPrimaryContainer,
    secondary = NightSecondary,
    onSecondary = NightOnSecondary,
    secondaryContainer = NightSecondaryContainer,
    onSecondaryContainer = NightOnSecondaryContainer,
    background = NightBackground,
    onBackground = NightOnBackground,
    surface = NightSurface,
    onSurface = NightOnSurface,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightOnSurfaceVariant,
    error = NightError,
)

private val LightColorScheme = lightColorScheme(
    primary = NightPrimaryContainer,
    onPrimary = NightOnPrimaryContainer,
    primaryContainer = NightPrimary,
    onPrimaryContainer = NightOnPrimary,
    secondary = NightSecondaryContainer,
    onSecondary = NightOnSecondaryContainer,
    secondaryContainer = NightSecondary,
    onSecondaryContainer = NightOnSecondary,
    background = NightBackground,
    onBackground = NightOnBackground,
    surface = NightSurface,
    onSurface = NightOnSurface,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightOnSurfaceVariant,
    error = NightError,
)

@Composable
fun MediaBusTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme || !dynamicColor) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
