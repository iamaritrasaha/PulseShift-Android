package com.pulseshift.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ============================================================================
// PulseShift theme: restrained instrument-panel Material 3 schemes.
// 60% charcoal canvas / 30% neutral surfaces / 10% amber focus, with semantic
// green/amber/red/cyan reserved for device state. Dynamic color stays opt-in
// so the product identity holds; DataStore default is false (see Phase C).
// ============================================================================

private val DarkColorScheme = darkColorScheme(
    background = Dark60CanvasBackground,
    onBackground = DarkTextPrimary,
    surface = Dark60CanvasBackground,
    onSurface = DarkTextPrimary,
    surfaceContainer = Dark30CardContainer,
    surfaceContainerHigh = Dark30CardContainerVariant,
    surfaceContainerHighest = Dark30CardContainerVariant,
    surfaceVariant = Dark30CardContainerVariant,
    onSurfaceVariant = DarkTextSecondary,
    outline = Dark30BorderDivider,
    outlineVariant = Dark30BorderDivider,
    primary = AccentAmber,
    onPrimary = AccentAmberOn,
    primaryContainer = AccentAmberContainer,
    onPrimaryContainer = AccentAmberOnContainer,
    secondary = HardwareTealActive,
    onSecondary = Color(0xFF04211E),
    secondaryContainer = Color(0xFF063A36),
    onSecondaryContainer = Color(0xFFB8F5EC),
    tertiary = HardwareCyanActive,
    onTertiary = Color(0xFF062A3C),
    tertiaryContainer = Color(0xFF0B3E56),
    onTertiaryContainer = Color(0xFFC7E7F8),
    error = HardwareRedError,
    onError = Color(0xFF3C0A0A),
    errorContainer = Color(0xFF571C1C),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColorScheme = lightColorScheme(
    background = Light60CanvasBackground,
    onBackground = LightTextPrimary,
    surface = Light60CanvasBackground,
    onSurface = LightTextPrimary,
    surfaceContainer = Light30CardContainer,
    surfaceContainerHigh = Light30CardContainerVariant,
    surfaceContainerHighest = Color(0xFFE0E4E9),
    surfaceVariant = Light30CardContainerVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = Light30BorderDivider,
    outlineVariant = Light30BorderDivider,
    primary = AccentAmberLight,
    onPrimary = AccentAmberOnLight,
    primaryContainer = AccentAmberContainerLight,
    onPrimaryContainer = Color(0xFF2C1A00),
    secondary = Color(0xFF0F766E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCF1EC),
    onSecondaryContainer = Color(0xFF04211E),
    tertiary = Color(0xFF0369A1),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE0F2FE),
    onTertiaryContainer = Color(0xFF062A3C),
    error = Color(0xFFB42318),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE4E2),
    onErrorContainer = Color(0xFF3C0A0A),
)

@Composable
fun PulseShiftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
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
