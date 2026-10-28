package io.github.vvcchh0.devicebridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WarmIvory = Color(0xFFF5F2E9)
private val Paper = Color(0xFFFFFEFA)
private val Charcoal = Color(0xFF202C2B)
private val MutedInk = Color(0xFF5C6B69)
private val Teal = Color(0xFF176B67)
private val TealPale = Color(0xFFD9ECE7)
private val Amber = Color(0xFF8A5A12)
private val AmberPale = Color(0xFFF5E9CC)
private val Coral = Color(0xFF9C4637)
private val CoralPale = Color(0xFFF4DFD8)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = TealPale,
    onPrimaryContainer = Color(0xFF123E3B),
    secondary = Color(0xFF587A73),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2EDE8),
    onSecondaryContainer = Color(0xFF243B36),
    tertiary = Amber,
    onTertiary = Color.White,
    tertiaryContainer = AmberPale,
    onTertiaryContainer = Color(0xFF503400),
    error = Coral,
    onError = Color.White,
    errorContainer = CoralPale,
    onErrorContainer = Color(0xFF542016),
    background = WarmIvory,
    onBackground = Charcoal,
    surface = Paper,
    onSurface = Charcoal,
    surfaceVariant = Color(0xFFE9E8DF),
    onSurfaceVariant = MutedInk,
    outline = Color(0xFF899693),
    outlineVariant = Color(0xFFD3D9D2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BCDC2),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF15534D),
    onPrimaryContainer = Color(0xFFB5E6DD),
    secondary = Color(0xFFB0CCC3),
    onSecondary = Color(0xFF1B3530),
    secondaryContainer = Color(0xFF354E48),
    onSecondaryContainer = Color(0xFFCCE8DF),
    tertiary = Color(0xFFEDC176),
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF64460D),
    onTertiaryContainer = Color(0xFFFFDFA7),
    error = Color(0xFFFFB4A4),
    onError = Color(0xFF690B00),
    errorContainer = Color(0xFF8C3023),
    onErrorContainer = Color(0xFFFFDAD2),
    background = Color(0xFF151C1B),
    onBackground = Color(0xFFE2E9E5),
    surface = Color(0xFF1D2625),
    onSurface = Color(0xFFE2E9E5),
    surfaceVariant = Color(0xFF34413F),
    onSurfaceVariant = Color(0xFFC0CCC7),
    outline = Color(0xFF899692),
    outlineVariant = Color(0xFF46534F),
)

@Composable
fun DeviceBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
