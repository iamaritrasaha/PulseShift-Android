package com.pulseshift.app.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// PulseShift Design System — restrained instrument-panel palette.
// 60% deep charcoal canvas, 30% neutral structural surfaces, 10% amber focus.
// Semantic green/amber/red/cyan are reserved for device state, never decoration.
// ============================================================================

// --- 60% canvas + 30% structure (dark) ---
val Dark60CanvasBackground = Color(0xFF0B0D12)
val Dark30CardContainer = Color(0xFF14171F)
val Dark30CardContainerVariant = Color(0xFF1C202B)
val Dark30BorderDivider = Color(0xFF2A2F3D)
val DarkTextPrimary = Color(0xFFF2F4F8)
val DarkTextSecondary = Color(0xFF9AA3B2)
val DarkTextTertiary = Color(0xFF6B7280)

// --- 60% canvas + 30% structure (light) ---
val Light60CanvasBackground = Color(0xFFF4F5F7)
val Light30CardContainer = Color(0xFFFFFFFF)
val Light30CardContainerVariant = Color(0xFFEAEDF1)
val Light30BorderDivider = Color(0xFFDCE1E7)
val LightTextPrimary = Color(0xFF16191F)
val LightTextSecondary = Color(0xFF5C6470)
val LightTextTertiary = Color(0xFF98A1AE)

// --- 10% focus accent: restrained amber ---
val AccentAmber = Color(0xFFFFB35C)
val AccentAmberOn = Color(0xFF3D2500)
val AccentAmberContainer = Color(0xFF4A2E00)
val AccentAmberOnContainer = Color(0xFFFFD9A0)

// --- Light-theme amber (darker for contrast on white surfaces) ---
val AccentAmberLight = Color(0xFF8C5A0C)
val AccentAmberOnLight = Color(0xFFFFFFFF)
val AccentAmberContainerLight = Color(0xFFFFDDB0)
val AccentAmberOnContainerLight = Color(0xFF2C1A00)

// --- Teal: reserved for "device active/connected" hardware state ---
val HardwareTealActive = Color(0xFF2DD4BF)

// --- Semantic hardware state colors (state only, never decoration) ---
val HardwareGreenVerified = Color(0xFF34D399)
val HardwareAmberWarning = Color(0xFFFBBF24)
val HardwareRedError = Color(0xFFF87171)
val HardwareCyanActive = Color(0xFF38BDF8)
val HardwareInactiveOff = Color(0xFF3F4654)
