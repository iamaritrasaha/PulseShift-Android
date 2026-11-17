package com.pulseshift.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.pulseshift.app.domain.model.RgbColor

@Composable
fun LedStripPreview(
    colors: List<RgbColor>,
    isPowerOn: Boolean,
    brightness: Int,
    selectedIndex: Int? = null,
    onSelectLed: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val effectiveColors = if (isPowerOn) {
        colors
    } else {
        List(6) { RgbColor(30, 30, 35) }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F0E14))
            .padding(vertical = 10.dp, horizontal = 8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // WS2812B Physical Mounting Rail Representation
            val isAllSelected = selectedIndex == null || selectedIndex == -1
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(Color(0xFF18171E), RoundedCornerShape(14.dp))
                    .border(
                        width = if (isAllSelected) 1.5.dp else 1.dp,
                        color = if (isAllSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .padding(horizontal = 10.dp)
            ) {
                effectiveColors.forEachIndexed { index, rgb ->
                    val isSelected = selectedIndex == index
                    val targetColor = if (isPowerOn) {
                        val factor = (brightness / 255f).coerceIn(0.15f, 1.0f)
                        Color(
                            red = (rgb.red / 255f) * factor,
                            green = (rgb.green / 255f) * factor,
                            blue = (rgb.blue / 255f) * factor
                        )
                    } else {
                        Color(0xFF23222A)
                    }

                    val animatedColor = animateColorAsState(
                        targetValue = targetColor,
                        animationSpec = tween(durationMillis = 180),
                        label = "LedColor_$index"
                    ).value

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onSelectLed(index) }
                    ) {
                        // Photorealistic Diffuser Glow Canvas
                        Canvas(modifier = Modifier.size(48.dp)) {
                            val center = Offset(size.width / 2, size.height / 2)
                            val radius = size.minDimension / 2.6f

                            if (isPowerOn && brightness > 15) {
                                // Diffuse radial bloom
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            animatedColor.copy(alpha = 0.55f * (brightness / 255f)),
                                            animatedColor.copy(alpha = 0.15f * (brightness / 255f)),
                                            Color.Transparent
                                        ),
                                        center = center,
                                        radius = radius * 1.7f
                                    ),
                                    radius = radius * 1.7f,
                                    center = center
                                )
                            }

                            // WS2812B Black 5050 Package Ring
                            drawCircle(
                                color = Color(0xFF111015),
                                radius = radius,
                                center = center
                            )

                            // Emitting Die / Silicone Lens
                            drawCircle(
                                color = animatedColor,
                                radius = radius * 0.82f,
                                center = center
                            )

                            // Inner Specular Reflection Highlight
                            drawCircle(
                                color = Color.White.copy(alpha = if (isPowerOn) 0.35f else 0.05f),
                                radius = radius * 0.32f,
                                center = center - Offset(radius * 0.2f, radius * 0.2f)
                            )

                            // Selection Ring
                            if (isSelected) {
                                drawCircle(
                                    color = Color.White,
                                    radius = radius + 4.dp.toPx(),
                                    center = center,
                                    style = Stroke(width = 2.dp.toPx())
                                )
                            }
                        }

                        // Index label
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (animatedColor.luminance() > 0.18f) Color.Black else Color.White
                        )
                    }
                }
            }
        }
    }
}
