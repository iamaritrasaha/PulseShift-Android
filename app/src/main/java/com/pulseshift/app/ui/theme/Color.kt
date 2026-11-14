package com.pulseshift.app.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// 60/30/10 Design System Color Palette for PulseShift
// 60% Base Canvas  : Deep Graphite Night (#0F1015) / Slate Light (#F8FAFC)
// 30% Structural   : Card Containers (#1B1D26), Surface Panels (#161820), Borders (#2B2E3D)
// 10% Focal Accent : Electric Violet (#8B5CF6 / #A78BFA), Cyber Cyan (#06B6D4)
// ============================================================================

// --- 60% Canvas & 30% Structure (Dark Theme) ---
val Dark60CanvasBackground = Color(0xFF0F1015)
val Dark30CardContainer = Color(0xFF1B1D26)
val Dark30CardContainerVariant = Color(0xFF222531)
val Dark30BorderDivider = Color(0xFF2B2E3D)
val DarkTextPrimary = Color(0xFFF3F4F6)
val DarkTextSecondary = Color(0xFF9CA3AF)

// --- 60% Canvas & 30% Structure (Light Theme) ---
val Light60CanvasBackground = Color(0xFFF8FAFC)
val Light30CardContainer = Color(0xFFFFFFFF)
val Light30CardContainerVariant = Color(0xFFF1F5F9)
val Light30BorderDivider = Color(0xFFE2E8F0)
val LightTextPrimary = Color(0xFF0F172A)
val LightTextSecondary = Color(0xFF64748B)

// --- 10% Focal Accent Colors (Shared & Dynamic Focal Points) ---
val AccentVioletPrimary = Color(0xFFA78BFA)
val AccentVioletOnPrimary = Color(0xFF1E1035)
val AccentVioletContainer = Color(0xFF2E1065)
val AccentVioletOnContainer = Color(0xFFDDD6FE)

val AccentCyanSecondary = Color(0xFF06B6D4)
val AccentCyanOnSecondary = Color(0xFF003640)
val AccentCyanContainer = Color(0xFF083344)
val AccentCyanOnContainer = Color(0xFFCFFAFE)

// Semantic Hardware State Colors
val HardwareGreenVerified = Color(0xFF10B981)
val HardwareAmberWarning = Color(0xFFF59E0B)
val HardwareRedError = Color(0xFFEF4444)
val HardwareCyanActive = Color(0xFF06B6D4)
val HardwareInactiveOff = Color(0xFF3F3F46)
