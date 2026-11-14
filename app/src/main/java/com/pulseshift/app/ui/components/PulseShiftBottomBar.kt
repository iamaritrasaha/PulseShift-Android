package com.pulseshift.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseshift.app.ui.navigation.Screen
import com.pulseshift.app.ui.theme.PulseShiftMonoTiny
import com.pulseshift.app.ui.theme.PulseShiftSpacing

/**
 * Compact technical automotive docking navigation bar.
 * Visually attached to screen content with razor-sharp top hairline border,
 * flush edge-to-edge docking, glowing active indicators, and standard 48dp touch targets.
 */
@Composable
fun PulseShiftBottomBar(
    screens: List<Screen>,
    currentRoute: String?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                screens.forEach { screen ->
                    val isSelected = currentRoute == screen.route
                    val animatedScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.05f else 1.0f,
                        animationSpec = tween(durationMillis = 180),
                        label = "scale"
                    )
                    val iconTint by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        animationSpec = tween(durationMillis = 180),
                        label = "iconTint"
                    )
                    val textTint by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        animationSpec = tween(durationMillis = 180),
                        label = "textTint"
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .height(PulseShiftSpacing.MinTouchTarget)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true, radius = 28.dp),
                                onClick = {
                                    // Tapping the already-selected tab must not
                                    // re-run navigate(popUpTo/restoreState): the
                                    // redundant call churns the back stack for
                                    // nothing and used to interrupt in-flight
                                    // destination transitions.
                                    if (!isSelected) onNavigate(screen)
                                }
                            )
                            .padding(vertical = 2.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Top glowing active indicator pill
                        Box(
                            modifier = Modifier
                                .height(2.5.dp)
                                .width(22.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                        )

                        // Icon with subtle scale animation
                        screen.icon?.let { iconVector ->
                            Icon(
                                imageVector = iconVector,
                                contentDescription = screen.title,
                                modifier = Modifier
                                    .size(22.dp)
                                    .scale(animatedScale),
                                tint = iconTint
                            )
                        }

                        // Monospace micro-label
                        Text(
                            text = when (screen) {
                                Screen.Home -> "DASH"
                                Screen.Lighting -> "LIGHTS"
                                Screen.Presets -> "PRESETS"
                                Screen.Diagnostics -> "DIAGS"
                                else -> screen.title.uppercase()
                            },
                            style = PulseShiftMonoTiny,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            letterSpacing = 0.8.sp,
                            color = textTint,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
