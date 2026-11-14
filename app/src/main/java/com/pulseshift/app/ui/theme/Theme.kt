package com.pulseshift.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

// 60/30/10 Color Scheme Mappings
private val DarkColorScheme = darkColorScheme(
    background = Dark60CanvasBackground,
    surface = Dark60CanvasBackground,
    surfaceContainer = Dark30CardContainer,
    surfaceVariant = Dark30CardContainer,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary,
    onSurfaceVariant = DarkTextSecondary,
    outline = Dark30BorderDivider,

    // 10% Focal Accents
    primary = AccentVioletPrimary,
    onPrimary = AccentVioletOnPrimary,
    primaryContainer = AccentVioletContainer,
    onPrimaryContainer = AccentVioletOnContainer,

    secondary = AccentCyanSecondary,
    onSecondary = AccentCyanOnSecondary,
    secondaryContainer = AccentCyanContainer,
    onSecondaryContainer = AccentCyanOnContainer
)

private val LightColorScheme = lightColorScheme(
    background = Light60CanvasBackground,
    surface = Light60CanvasBackground,
    surfaceContainer = Light30CardContainer,
    surfaceVariant = Light30CardContainerVariant,
    onBackground = LightTextPrimary,
    onSurface = LightTextPrimary,
    onSurfaceVariant = LightTextSecondary,
    outline = Light30BorderDivider,

    // 10% Focal Accents
    primary = AccentVioletPrimary,
    onPrimary = AccentVioletOnPrimary,
    primaryContainer = AccentVioletContainer,
    onPrimaryContainer = AccentVioletOnContainer,

    secondary = AccentCyanSecondary,
    onSecondary = AccentCyanOnSecondary,
    secondaryContainer = AccentCyanContainer,
    onSecondaryContainer = AccentCyanOnContainer
)

@Composable
fun PulseShiftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Default false to enforce harmonized 60/30/10 motorcycle UI theme
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PulseShiftTypography,
        shapes = PulseShiftShapes,
        content = content
    )
}
