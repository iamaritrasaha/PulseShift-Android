package com.pulseshift.app.ui.screens.lighting

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.ShiftLightConfig
import com.pulseshift.app.domain.model.StartupAnimation
import com.pulseshift.app.domain.model.WarningStyle
import com.pulseshift.app.ui.components.HsvColorWheel
import com.pulseshift.app.ui.components.PulseShiftSlider
import com.pulseshift.app.ui.components.SectionCard
import com.pulseshift.app.ui.components.StepperControl
import com.pulseshift.app.ui.theme.HardwareAmberWarning
import com.pulseshift.app.ui.theme.HardwareGreenVerified
import com.pulseshift.app.ui.theme.HardwareRedError
import com.pulseshift.app.ui.theme.PulseShiftMonoMedium
import com.pulseshift.app.ui.theme.PulseShiftMonoSmall
import com.pulseshift.app.ui.theme.PulseShiftMonoTiny
import com.pulseshift.app.ui.theme.PulseShiftRadius
import com.pulseshift.app.ui.theme.PulseShiftShapeTokens
import com.pulseshift.app.ui.theme.PulseShiftSpacing

private val QUICK_PALETTE = listOf(
    RgbColor.NeonGreen to "Neon Green",
    RgbColor.AmberWarning to "Amber",
    RgbColor.SunsetOrange to "Sunset",
    RgbColor.RacingRed to "Redline",
    RgbColor(6, 182, 212) to "Cyan Volt",
    RgbColor(99, 102, 241) to "Electric Blue",
    RgbColor(168, 85, 247) to "Purple",
    RgbColor.PureWhite to "Pure White"
)

/** Color tab: global color when "All LEDs" is selected, per-LED otherwise. */
@Composable
fun ColorsPanel(
    viewModel: LightingViewModel,
    lighting: LightingState,
    selectedLed: Int
) {
    val targetColor = if (selectedLed == -1) lighting.globalColor
    else lighting.ledColors.getOrElse(selectedLed) { lighting.globalColor }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.ControlGap)
    ) {
        SectionCard(
            title = if (selectedLed == -1) "Color Palette (All 6 LEDs)" else "LED ${selectedLed + 1} Color",
            subtitle = "Choose from automotive track swatches or fine-tune with the HSV wheel"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)) {
                // Quick Swatches Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QUICK_PALETTE.forEach { (color, _) ->
                        val isSelected = targetColor.red == color.red && targetColor.green == color.green && targetColor.blue == color.blue
                        val composeColor = Color(color.red, color.green, color.blue)

                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(composeColor)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    shape = CircleShape
                                )
                                .clickable { viewModel.setColor(color) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = if ((color.red * 299 + color.green * 587 + color.blue * 114) / 1000 > 150) Color.Black else Color.White
                                )
                            }
                        }
                    }
                }

                // HSV Color Wheel
                HsvColorWheel(
                    selectedColor = targetColor,
                    onColorChanged = { viewModel.setColor(it) }
                )
            }
        }

        // Master Brightness Card with quick % selectors
        SectionCard(
            title = "Master Brightness",
            subtitle = "Hardware PWM output scaling (0–255)"
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.LightMode,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(PulseShiftSpacing.SmallLabel))
                        Text(
                            text = "${(lighting.brightness * 100) / 255}%",
                            style = PulseShiftMonoMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = "${lighting.brightness} / 255",
                        style = PulseShiftMonoSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

            PulseShiftSlider(
                value = lighting.brightness.toFloat(),
                onValueChange = { viewModel.setBrightness(it.toInt()) },
                valueRange = 0f..255f,
                modifier = Modifier.fillMaxWidth()
            )

                // Quick Preset Buttons: 25%, 50%, 75%, 100%
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallLabel)
                ) {
                    listOf(25 to 64, 50 to 128, 75 to 191, 100 to 255).forEach { (pct, value) ->
                        val isCurrent = (lighting.brightness in (value - 10)..(value + 10))
                        OutlinedButton(
                            onClick = { viewModel.setBrightness(value) },
                            shape = PulseShiftShapeTokens.Control,
                            border = if (isCurrent) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                            colors = if (isCurrent) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                     else ButtonDefaults.outlinedButtonColors(),
                            modifier = Modifier.weight(1f).height(38.dp)
                        ) {
                            Text("$pct%", style = PulseShiftMonoTiny, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

/** Effects tab: effect picker + speed + direction. */
@Composable
fun EffectsPanel(
    viewModel: LightingViewModel,
    lighting: LightingState
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.ControlGap)
    ) {
        SectionCard(
            title = "Illumination Effect",
            subtitle = "Select an operating mode. Effect 5 provides uncalibrated live preview."
        ) {
        Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallLabel)) {
            EffectType.entries.forEach { effect ->
                val isSelected = lighting.effect == effect
                val cardBorder = if (isSelected) {
                    BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                }
                val cardBg = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                }

                Surface(
                    shape = PulseShiftShapeTokens.Card,
                    color = cardBg,
                    border = cardBorder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PulseShiftShapeTokens.Card)
                        .clickable { viewModel.setEffect(effect) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PulseShiftSpacing.CardPadding, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = effect.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                if (effect == EffectType.PROVISIONAL_PREVIEW) {
                                    Spacer(Modifier.width(PulseShiftSpacing.SmallLabel))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = HardwareGreenVerified.copy(alpha = 0.18f),
                                        border = BorderStroke(1.dp, HardwareGreenVerified.copy(alpha = 0.4f))
                                    ) {
                                        Text(
                                            "RECOMMENDED",
                                            style = PulseShiftMonoTiny,
                                            color = HardwareGreenVerified,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = effect.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                )
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    SectionCard(
        title = "Animation Speed",
        subtitle = "Cycle speed for dynamic effects (0–100%)"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(PulseShiftSpacing.SmallLabel))
                    Text(
                        text = "${lighting.effectSpeed}%",
                        style = PulseShiftMonoMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                StepperControl(
                    value = lighting.effectSpeed,
                    onValueChange = { viewModel.setEffectSpeed(it) },
                    min = 0,
                    max = 100,
                    step = 5,
                    unit = "%"
                )
            }

            PulseShiftSlider(
                value = lighting.effectSpeed.toFloat(),
                onValueChange = { viewModel.setEffectSpeed(it.toInt()) },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    SectionCard(
        title = "LED Traversal Direction",
        subtitle = "Controls animation flow direction across the physical WS2812B rail"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (lighting.isDirectionReversed) "Reversed: LED 6 ➔ LED 1" else "Forward: LED 1 ➔ LED 6",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (lighting.isDirectionReversed) "Inverts ignition chase for right-to-left handlebar mount"
                           else "Standard left-to-right cockpit orientation",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = lighting.isDirectionReversed,
                onCheckedChange = { viewModel.setLedDirection(if (it) 1 else 0) }
            )
        }
    }
}
}

/** RPM bar tab: per-LED thresholds, quick profile button, warning RPM/pattern/color. */
@Composable
fun RpmBarPanel(
    viewModel: LightingViewModel,
    config: ShiftLightConfig
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.ControlGap)
    ) {
        // High-Tech Quick 1k-6k Action Card
        Surface(
        shape = PulseShiftShapeTokens.Card,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(PulseShiftSpacing.CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.AutoMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(PulseShiftSpacing.SmallLabel))
                Text(
                    text = "Recommended 1k–6k Racing Profile",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(Modifier.height(PulseShiftSpacing.Micro))
            Text(
                text = "Stages [1000, 2000, 3000, 4000, 5000, 6000] RPM thresholds with 6,500 RPM shift warning.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(PulseShiftSpacing.RelatedControls))
            Button(
                onClick = { viewModel.apply1000To6000Profile() },
                modifier = Modifier.fillMaxWidth().height(PulseShiftSpacing.MinTouchTarget),
                shape = PulseShiftShapeTokens.Control,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Rounded.FlashOn, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(PulseShiftSpacing.SmallLabel))
                Text("Apply 1,000–6,000 RPM Profile", fontWeight = FontWeight.Bold)
            }
        }
    }

    // Monotonic RPM Thresholds Table
    SectionCard(
        title = "Per-LED RPM Thresholds",
        subtitle = "Activation thresholds in strictly ascending order with anti-chatter hysteresis"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            config.thresholds.forEachIndexed { index, rpm ->
                val ledColor = config.colors.getOrElse(index) { RgbColor.NeonGreen }
                val composeColor = Color(ledColor.red, ledColor.green, ledColor.blue)

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(composeColor)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "LED ${index + 1}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    val newRpm = (rpm - 100).coerceAtLeast(500)
                                    viewModel.updateThreshold(index, newRpm)
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Rounded.Remove, contentDescription = "Decrease", modifier = Modifier.size(18.dp))
                            }

                            Text(
                                text = "$rpm RPM",
                                style = PulseShiftMonoMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )

                            IconButton(
                                onClick = {
                                    val newRpm = (rpm + 100).coerceAtMost(15000)
                                    viewModel.updateThreshold(index, newRpm)
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = "Increase", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            if (!config.isStrictlyAscending()) {
                Spacer(Modifier.height(4.dp))
                Card(
                    shape = PulseShiftShapeTokens.Card,
                    colors = CardDefaults.cardColors(containerColor = HardwareRedError.copy(alpha = 0.15f)),
                    border = BorderStroke(1.dp, HardwareRedError.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(PulseShiftSpacing.CardPadding),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = HardwareRedError)
                        Spacer(Modifier.width(PulseShiftSpacing.SmallLabel))
                        Text(
                            text = "Thresholds must ascend monotonically (LED 1 < LED 2 < ... < LED 6) and Shift Warning must be >= LED 6.",
                            style = MaterialTheme.typography.bodySmall,
                            color = HardwareRedError
                        )
                    }
                }
            }
        }
    }

        WarningCard(viewModel, config)
    }
}

@Composable
private fun WarningCard(viewModel: LightingViewModel, config: ShiftLightConfig) {
    var warningRpm by remember(config.warningRpm) { mutableIntStateOf(config.warningRpm) }
    var selectedStyle by remember(config.warningStyle) { mutableStateOf(config.warningStyle) }
    val warningColor = config.colors.getOrNull(5) ?: RgbColor.RacingRed

    SectionCard(
        title = "Shift Warning & Redline Flash",
        subtitle = "Triggered when engine RPM exceeds critical threshold"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)) {
            val minWarning = (config.thresholds.getOrNull(5) ?: 1000)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = HardwareRedError,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(PulseShiftSpacing.SmallLabel))
                    Text(
                        text = "$warningRpm RPM",
                        style = PulseShiftMonoMedium,
                        color = HardwareRedError
                    )
                }
                StepperControl(
                    value = warningRpm,
                    onValueChange = {
                        warningRpm = it
                        viewModel.setShiftWarning(warningRpm, selectedStyle, warningColor)
                    },
                    min = minWarning,
                    max = 16000,
                    step = 100,
                    unit = "RPM"
                )
            }

            Text("Flash Strobe Pattern", style = MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallLabel)
            ) {
                WarningStyle.entries.forEach { style ->
                    FilterChip(
                        selected = selectedStyle == style,
                        onClick = {
                            selectedStyle = style
                            viewModel.setShiftWarning(warningRpm, style, warningColor)
                        },
                        label = { Text(style.label, style = PulseShiftMonoTiny) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text("Warning Light Color", style = MaterialTheme.typography.labelMedium)
            HsvColorWheel(
                selectedColor = warningColor,
                onColorChanged = { color ->
                    viewModel.setShiftWarning(warningRpm, selectedStyle, color)
                }
            )
        }
    }
}

/** Startup tab: boot animation choice + duration (debounced writes). */
@Composable
fun StartupPanel(
    viewModel: LightingViewModel,
    lighting: LightingState
) {
    var durationMs by remember(lighting.startupDurationMs) {
        mutableFloatStateOf(lighting.startupDurationMs.toFloat())
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.ControlGap)
    ) {
        SectionCard(
            title = "Power-On Ignition Animation",
            subtitle = "Plays once upon ESP32 startup before engaging live tachometer illumination"
        ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StartupAnimation.entries.forEach { animation ->
                val isSelected = lighting.startupAnimation == animation
                val cardBorder = if (isSelected) {
                    BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                }
                val cardBg = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                }

                Surface(
                    shape = PulseShiftShapeTokens.Card,
                    color = cardBg,
                    border = cardBorder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PulseShiftShapeTokens.Card)
                        .clickable {
                            viewModel.setStartupAnimation(animation, durationMs.toInt())
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PulseShiftSpacing.CardPadding, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = animation.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(Modifier.width(PulseShiftSpacing.SmallLabel))
                                Text(
                                    text = "— ${animation.subtitle}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = animation.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                )
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    SectionCard(
        title = "Animation Duration",
        subtitle = "Length of the ignition sequence in seconds"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format("%.1f seconds (%d ms)", durationMs / 1000f, durationMs.toInt()),
                    style = PulseShiftMonoMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                StepperControl(
                    value = durationMs.toInt(),
                    onValueChange = {
                        durationMs = it.toFloat()
                        viewModel.setStartupAnimation(lighting.startupAnimation, it)
                    },
                    min = 500,
                    max = 6000,
                    step = 250,
                    unit = "ms"
                )
            }

            PulseShiftSlider(
                value = durationMs,
                onValueChange = { durationMs = it },
                onValueChangeFinished = {
                    viewModel.setStartupAnimation(lighting.startupAnimation, durationMs.toInt())
                },
                valueRange = 500f..6000f,
                modifier = Modifier.fillMaxWidth()
            )

            // Quick Duration Preset Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.SmallLabel)
            ) {
                listOf(1000 to "1.0s", 1500 to "1.5s", 2000 to "2.0s", 3000 to "3.0s", 4000 to "4.0s").forEach { (ms, label) ->
                    val isCurrent = (durationMs.toInt() in (ms - 100)..(ms + 100))
                    OutlinedButton(
                        onClick = {
                            durationMs = ms.toFloat()
                            viewModel.setStartupAnimation(lighting.startupAnimation, ms)
                        },
                        shape = PulseShiftShapeTokens.Control,
                        border = if (isCurrent) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        colors = if (isCurrent) ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                 else ButtonDefaults.outlinedButtonColors(),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Text(label, style = PulseShiftMonoTiny, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}
}
