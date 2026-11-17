package com.pulseshift.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pulseshift.app.R

// Bundled Google Sans is optional to the resolver. If it cannot load, Compose uses system text.
@OptIn(ExperimentalTextApi::class)
private val GoogleSansHeadings = FontFamily(
    listOf(FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(
            resId = R.font.google_sans,
            weight = weight,
            loadingStrategy = FontLoadingStrategy.OptionalLocal,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
        )
    }
)

val PulseShiftTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = GoogleSansHeadings,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = GoogleSansHeadings, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp
    ),
    titleLarge = TextStyle(
        fontFamily = GoogleSansHeadings, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp
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
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.25.sp
    )
)

// Monospace technical styles (not part of MaterialTheme typography slots;
// applied explicitly to numeric readouts, CAN IDs, terminal output).

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
    fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.sp
)
