package com.pulseshift.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

// ============================================================================
// PulseShift Typography. Sans styles carry UI chrome; monospace styles carry
// every technical value (RPM, offsets, CAN IDs, terminal output) so numbers
// align and read like an instrument panel. System monospace: license-clean.
// ============================================================================

val PulseShiftTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.25.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp
    )
)

// Monospace technical styles (not part of MaterialTheme typography slots;
// applied explicitly to numeric readouts, CAN IDs, terminal output).
val PulseShiftMonoDisplay = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 52.sp
)
val PulseShiftMonoLarge = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp
)
val PulseShiftMonoMedium = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp
)
val PulseShiftMonoSmall = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp
)
val PulseShiftMonoTiny = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp
)
