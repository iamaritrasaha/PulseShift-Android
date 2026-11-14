package com.pulseshift.app.ui.screens.lighting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.ShiftLightConfig
import com.pulseshift.app.domain.model.StartupAnimation
import com.pulseshift.app.domain.model.WarningStyle
import com.pulseshift.app.ui.components.HsvColorWheel
import com.pulseshift.app.ui.components.SectionCard
import com.pulseshift.app.ui.components.StepperControl
import com.pulseshift.app.ui.theme.HardwareRedError
import com.pulseshift.app.ui.theme.PulseShiftMonoMedium
import com.pulseshift.app.ui.theme.PulseShiftMonoSmall
import com.pulseshift.app.ui.theme.PulseShiftSpacing

/** Color tab: global color when "All LEDs" is selected, per-LED otherwise. */
@Composable
fun ColorsPanel(
    viewModel: LightingViewModel,
    lighting: LightingState,
    selectedLed: Int
) {
    SectionCard(
        title = if (selectedLed == -1) "Global Color (All 6 LEDs)" else "LED ${selectedLed + 1} Color",
        subtitle = "Tap individual LEDs on the preview strip above to target a single pixel"
    ) {
        HsvColorWheel(
            selectedColor = if (selectedLed == -1) lighting.globalColor
            else lighting.ledColors.getOrElse(selectedLed) { lighting.globalColor },
            onColorChanged = { viewModel.setColor(it) }
        )
    }

    SectionCard(
        title = "Master Brightness",
        subtitle = "Hardware PWM output scaling (0–255)"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)) {
            StepperControl(
                value = lighting.brightness,
                onValueChange = { viewModel.setBrightness(it) },
                min = 0,
                max = 255,
                step = 5,
                unit = "/ 255"
            )
            Slider(
                value = lighting.brightness.toFloat(),
                onValueChange = { viewModel.setBrightness(it.toInt()) },
                valueRange = 0f..255f
            )
        }
    }
}

/** Effects tab: effect picker + speed + direction. */
@Composable
fun EffectsPanel(
    viewModel: LightingViewModel,
    lighting: LightingState
) {
    SectionCard(
        title = "Lighting Effect",
        subtitle = "Effect 4: Verified RPM bar · Effect 5: Provisional live preview"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)) {
            EffectType.entries.forEach { effect ->
                FilterChip(
                    selected = lighting.effect == effect,
                    onClick = { viewModel.setEffect(effect) },
                    label = {
                        Column(modifier = Modifier.padding(vertical = PulseShiftSpacing.Micro)) {
                            Text(effect.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                effect.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { role = Role.RadioButton }
                )
            }
        }
    }

    SectionCard(
        title = "Animation Speed",
        subtitle = "Cycle rate for breathing, cycle, and chase effects (0–100)"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)) {
            StepperControl(
                value = lighting.effectSpeed,
                onValueChange = { viewModel.setEffectSpeed(it) },
                min = 0,
                max = 100,
                step = 5,
                unit = "%"
            )
            Slider(
                value = lighting.effectSpeed.toFloat(),
                onValueChange = { viewModel.setEffectSpeed(it.toInt()) },
                valueRange = 0f..100f
            )
        }
    }

    SectionCard(
        title = "LED Direction",
        subtitle = "Controls animation flow direction across the light bar"
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (lighting.isDirectionReversed) "Reversed (LED 6 → LED 1)" else "Forward (LED 1 → LED 6)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = lighting.isDirectionReversed,
                onCheckedChange = { viewModel.setLedDirection(if (it) 1 else 0) }
            )
        }
    }
}

/** RPM bar tab: per-LED thresholds, quick profile button, warning RPM/pattern/color. */
@Composable
fun RpmBarPanel(
    viewModel: LightingViewModel,
    config: ShiftLightConfig
) {
    // 1k-6k Quick Action Card
    SectionCard(
        title = "Recommended RPM Profile",
        subtitle = "Atomic hardware staging for standard 1k–6k tachometer operation"
    ) {
        Button(
            onClick = { viewModel.apply1000To6000Profile() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        ) {
            Icon(Icons.Rounded.AutoMode, contentDescription = null)
            Spacer(Modifier.width(PulseShiftSpacing.SmallLabel))
            Text("Apply 1,000–6,000 RPM Profile", style = MaterialTheme.typography.labelLarge)
        }
    }

    SectionCard(
        title = "Per-LED RPM Thresholds",
        subtitle = "Activation thresholds in strictly ascending order (hysteresis prevents chatter)"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)) {
            config.thresholds.forEachIndexed { index, rpm ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(PulseShiftSpacing.RelatedControls)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("LED ${index + 1}", style = PulseShiftMonoSmall)
                            Text("$rpm RPM", style = PulseShiftMonoSmall)
                        }
                        StepperControl(
                            value = rpm,
                            onValueChange = { viewModel.updateThreshold(index, it) },
                            min = 500,
                            max = 12000,
                            step = 100,
                            unit = "RPM"
                        )
                    }
                }
            }

            if (!config.isStrictlyAscending()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = HardwareRedError.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(PulseShiftSpacing.RelatedControls),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Warning, contentDescription = null, tint = HardwareRedError)
                        Spacer(Modifier.width(PulseShiftSpacing.SmallLabel))
                        Text(
                            "Thresholds must ascend monotonically and Warning RPM must be >= LED 6 threshold.",
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

@Composable
private fun WarningCard(viewModel: LightingViewModel, config: ShiftLightConfig) {
    var warningRpm by remember(config.warningRpm) { mutableIntStateOf(config.warningRpm) }
    var selectedStyle by remember(config.warningStyle) { mutableStateOf(config.warningStyle) }
    val warningColor = config.colors.getOrNull(5) ?: RgbColor.RacingRed

    SectionCard(
        title = "Shift Warning & Flash",
        subtitle = "Triggered when engine RPM meets or exceeds this critical threshold"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)) {
            val minWarning = (config.thresholds.getOrNull(5) ?: 1000)
            StepperControl(
                value = warningRpm,
                onValueChange = {
                    warningRpm = it
                    viewModel.setShiftWarning(warningRpm, selectedStyle, warningColor)
                },
                min = minWarning,
                max = 15000,
                step = 100,
                unit = "RPM"
            )

            Text("Warning Flash Pattern", style = MaterialTheme.typography.labelMedium)
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
                        label = { Text(style.label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text("Warning Flash Color", style = MaterialTheme.typography.labelMedium)
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

    SectionCard(
        title = "Power-On Startup Animation",
        subtitle = "Plays once upon ESP32 controller boot before engaging RPM mode"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)) {
            StartupAnimation.entries.forEach { animation ->
                FilterChip(
                    selected = lighting.startupAnimation == animation,
                    onClick = {
                        viewModel.setStartupAnimation(animation, durationMs.toInt())
                    },
                    label = {
                        Column(modifier = Modifier.padding(vertical = PulseShiftSpacing.Micro)) {
                            Text(
                                "${animation.displayName} — ${animation.subtitle}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                animation.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { role = Role.RadioButton }
                )
            }
        }
    }

    SectionCard(
        title = "Animation Duration",
        subtitle = "Total running time of startup sequence"
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)) {
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
            Slider(
                value = durationMs,
                onValueChange = { durationMs = it },
                onValueChangeFinished = {
                    viewModel.setStartupAnimation(lighting.startupAnimation, durationMs.toInt())
                },
                valueRange = 500f..6000f
            )
        }
    }
}
