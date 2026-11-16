package com.pulseshift.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.pulseshift.app.R

// ============================================================================
// PulseShift Typography.
// Primary typeface: Google Sans (via Google Fonts provider with system SansSerif fallback).
// Technical typeface: System Monospace for instrument-panel readouts (RPM, offsets,
// CAN IDs, terminal output).
// ============================================================================

private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

private val googleSansFont = GoogleFont("Google Sans")

val GoogleSansFamily: FontFamily = try {
    FontFamily(
        Font(googleFont = googleSansFont, fontProvider = fontProvider, weight = FontWeight.Normal),
        Font(googleFont = googleSansFont, fontProvider = fontProvider, weight = FontWeight.Medium),
        Font(googleFont = googleSansFont, fontProvider = fontProvider, weight = FontWeight.SemiBold),
        Font(googleFont = googleSansFont, fontProvider = fontProvider, weight = FontWeight.Bold)
    )
} catch (_: Throwable) {
    FontFamily.SansSerif
}

val PulseShiftTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = GoogleSansFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = GoogleSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = GoogleSansFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = GoogleSansFamily, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = GoogleSansFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = GoogleSansFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.25.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = GoogleSansFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = GoogleSansFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = GoogleSansFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = GoogleSansFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = GoogleSansFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp
    )
)

// Monospace technical styles (not part of MaterialTheme typography slots;
// applied explicitly to numeric readouts, CAN IDs, terminal output).
// Line heights and letter spacing are adjusted so glyphs never clip even on font scaling.
val PulseShiftMonoDisplay = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = 0.sp
)
val PulseShiftMonoLarge = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp
)
val PulseShiftMonoMedium = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp
)
val PulseShiftMonoSmall = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp
)
val PulseShiftMonoTiny = TextStyle(
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.sp
)
