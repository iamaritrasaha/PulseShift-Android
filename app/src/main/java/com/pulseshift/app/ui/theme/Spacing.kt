package com.pulseshift.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * PulseShift Design System Spacing Tokens.
 * Enforces consistent rhythm, accessible touch targets, and proper screen margins.
 */
object PulseShiftSpacing {
    /** Screen horizontal margins (20dp standard). */
    val ScreenHorizontal: Dp = 20.dp

    /** Top/bottom screen margin padding (16dp). */
    val ScreenVertical: Dp = 16.dp

    /** Normal vertical spacing between major architectural sections (24dp). */
    val SectionSpacing: Dp = 24.dp

    /** Standard card interior padding (18dp). */
    val CardPadding: Dp = 18.dp

    /** Compact card interior padding for dense technical tables (16dp). */
    val CardPaddingCompact: Dp = 16.dp

    /** Spacing between related controls inside a section/group (12dp). */
    val RelatedControls: Dp = 12.dp
    val ControlGap: Dp = 12.dp

    /** Spacing between small labels, chips, and their parent inputs (8dp). */
    val SmallLabel: Dp = 8.dp
    val SmallGap: Dp = 8.dp

    /** Micro spacing for tight element groupings (4dp). */
    val Micro: Dp = 4.dp

    /** Minimum accessible touch target according to Material 3 & WCAG guidelines (48dp). */
    val MinTouchTarget: Dp = 48.dp

    /** Bottom extra padding for scrollable screens so content ends naturally above navigation bar (16dp). */
    val BottomScrollPadding: Dp = 16.dp
}

