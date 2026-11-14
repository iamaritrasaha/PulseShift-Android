package com.pulseshift.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.pulseshift.app.domain.model.RgbColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun HsvColorWheel(
    selectedColor: RgbColor,
    onColorChanged: (RgbColor) -> Unit,
    modifier: Modifier = Modifier
) {
    val hsv = remember(selectedColor) { selectedColor.toHsv() }
    var currentHue by remember(selectedColor) { mutableFloatStateOf(hsv[0]) }
    var currentSat by remember(selectedColor) { mutableFloatStateOf(hsv[1]) }
    var currentVal by remember(selectedColor) { mutableFloatStateOf(hsv[2]) }

    val quickColors = listOf(
        RgbColor.RacingRed,
        RgbColor.SunsetOrange,
        RgbColor.AmberWarning,
        RgbColor.NeonGreen,
        RgbColor.ElectricCyan,
        RgbColor.DeepBlue,
        RgbColor.BrandViolet,
        RgbColor.PureWhite
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        // Interactive Circular Hue Wheel
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(220.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .size(200.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val touch = change.position - center
                            var angle = Math.toDegrees(atan2(touch.y.toDouble(), touch.x.toDouble())).toFloat()
                            if (angle < 0) angle += 360f
                            currentHue = angle
                            val newColor = RgbColor.fromHsv(currentHue, currentSat, currentVal)
                            onColorChanged(newColor)
                        }
                    }
            ) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = size.minDimension / 2f - 16.dp.toPx()

                // Sweep Gradient Spectrum Ring
                val hueColors = listOf(
                    Color.Red, Color.Yellow, Color.Green,
                    Color.Cyan, Color.Blue, Color.Magenta, Color.Red
                )
                drawCircle(
                    brush = Brush.sweepGradient(hueColors, center = center),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 28.dp.toPx(), cap = StrokeCap.Round)
                )

                // Current Hue Thumb Indicator
                val angleRad = Math.toRadians(currentHue.toDouble())
                val thumbX = center.x + radius * cos(angleRad).toFloat()
                val thumbY = center.y + radius * sin(angleRad).toFloat()

                drawCircle(
                    color = Color.White,
                    radius = 12.dp.toPx(),
                    center = Offset(thumbX, thumbY)
                )
                drawCircle(
                    color = Color(selectedColor.red, selectedColor.green, selectedColor.blue),
                    radius = 9.dp.toPx(),
                    center = Offset(thumbX, thumbY)
                )
            }

            // Center Preview & Hex Readout
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(selectedColor.red, selectedColor.green, selectedColor.blue))
                        .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
                Text(
                    text = selectedColor.hexString,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // Saturation Slider
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Saturation", style = MaterialTheme.typography.labelMedium)
                Text("${(currentSat * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            }
            Slider(
                value = currentSat,
                onValueChange = {
                    currentSat = it
                    onColorChanged(RgbColor.fromHsv(currentHue, currentSat, currentVal))
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        }

        // Quick Swatches
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            quickColors.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(color.red, color.green, color.blue))
                        .border(
                            width = if (selectedColor == color) 2.5.dp else 1.dp,
                            color = if (selectedColor == color) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.2f),
                            shape = CircleShape
                        )
                        .clickable { onColorChanged(color) }
                )
            }
        }
    }
}
