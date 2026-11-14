package com.pulseshift.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Standardized PulseShift Corner Radii Tokens.
 * Consistent corner geometry across cards, modals, controls, and chips.
 */
object PulseShiftRadius {
    /** Micro elements, tags, and badges (4dp). */
    val Micro: Dp = 4.dp

    /** Small chips, pills, and nested indicators (8dp). */
    val Small: Dp = 8.dp

    /** Interactive controls, buttons, and text fields (12dp). */
    val Control: Dp = 12.dp

    /** Standard card corner radius (16dp). */
    val Card: Dp = 16.dp

    /** Standard dialogs, modal sheets, and large floating surfaces (24dp). */
    val Modal: Dp = 24.dp

    /** Large architectural containers and top-sheet corners (28dp). */
    val Large: Dp = 28.dp

    /** Fully circular or pill elements (999dp). */
    val Pill: Dp = 999.dp
}

object PulseShiftShapeTokens {
    val Micro = RoundedCornerShape(PulseShiftRadius.Micro)
    val Small = RoundedCornerShape(PulseShiftRadius.Small)
    val Control = RoundedCornerShape(PulseShiftRadius.Control)
    val Card = RoundedCornerShape(PulseShiftRadius.Card)
    val Modal = RoundedCornerShape(PulseShiftRadius.Modal)
    val Large = RoundedCornerShape(PulseShiftRadius.Large)
}

// Material 3 Expressive Shapes (Standardized PulseShift Radii)
val PulseShiftShapes = Shapes(
    extraSmall = RoundedCornerShape(PulseShiftRadius.Micro),
    small = RoundedCornerShape(PulseShiftRadius.Small),
    medium = RoundedCornerShape(PulseShiftRadius.Card), // Standard card radius: 16.dp
    large = RoundedCornerShape(PulseShiftRadius.Modal),  // Standard modal radius: 24.dp
    extraLarge = RoundedCornerShape(PulseShiftRadius.Large)
)

