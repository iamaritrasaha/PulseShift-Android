package com.pulseshift.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pulseshift.app.domain.model.ShiftLightConfig

@Composable
fun ShiftLightBar(
    currentRpm: Int,
    config: ShiftLightConfig,
    modifier: Modifier = Modifier
) {
    val isWarning = currentRpm >= config.warningRpm

    val infiniteTransition = rememberInfiniteTransition(label = "FlashWarning")
    val flashAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "WarningStrobe"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF14131A))
            .padding(16.dp)
    ) {
        // Shift Bar Array
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            config.thresholds.forEachIndexed { index, threshold ->
                val isLit = currentRpm >= threshold
                val baseColor = config.colors[index]
                val composeColor = Color(baseColor.red, baseColor.green, baseColor.blue)

                val effectiveAlpha = if (isWarning) flashAlpha else (if (isLit) 1.0f else 0.15f)
                val displayColor = if (isLit) composeColor.copy(alpha = effectiveAlpha) else Color(0xFF282733)

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(displayColor)
                            .border(
                                width = 1.5.dp,
                                color = if (isLit) composeColor else Color(0xFF3B394A),
                                shape = CircleShape
                            )
                    )
                    Text(
                        text = "${threshold / 1000}k",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // Warning Status Indicator
        if (isWarning) {
            Text(
                text = "SHIFT NOW! (${config.warningRpm} RPM)",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFFEF4444),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
