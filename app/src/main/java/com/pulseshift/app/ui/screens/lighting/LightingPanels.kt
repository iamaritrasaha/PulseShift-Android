package com.pulseshift.app.ui.screens.lighting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.pulseshift.app.ui.theme.PulseShiftMonoMedium
import com.pulseshift.app.ui.theme.PulseShiftMonoSmall

/** Color tab: global color when "All LEDs" is selected, per-LED otherwise. */
@Composable
fun ColorsPanel(
    viewModel: LightingViewModel,
    lighting: LightingState,
    selectedLed: Int
) {
    SectionCard(
        title = if (selectedLed == -1) "Global Color (all 6 LEDs)" else "LED ${selectedLed + 1} Color",
        subtitle = "Tap LEDs on the preview strip above to target a single pixel"
    ) {
        HsvColorWheel(
            selectedColor = if (selectedLed == -1) lighting.globalColor
            else lighting.ledColors.getOrElse(selectedLed) { lighting.globalColor },
            onColorChanged = { viewModel.setColor(it) }
        )
    }
    SectionCard(title = "Master Brightness") {
        Text("${lighting.brightness}", style = PulseShiftMonoMedium)
        Slider(
            value = lighting.brightness.toFloat(),
            onValueChange = { viewModel.setBrightness(it.toInt()) },
            valueRange = 0f..255f
        )
    }
}

/** Effects tab: effect picker + speed + direction. */
@Composable
fun EffectsPanel(
    viewModel: LightingViewModel,
    lighting: LightingState
) {
    SectionCard(title = "Effect", subtitle = "4 = verified RPM bar · 5 = unverified provisional preview") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EffectType.entries.forEach { effect ->
                FilterChip(
                    selected = lighting.effect == effect,
                    onClick = { viewModel.setEffect(effect) },
                    label = {
                        Column {
                            Text(effect.displayName)
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
    SectionCard(title = "Effect Speed") {
        Text("${lighting.effectSpeed}", style = PulseShiftMonoMedium)
        Slider(
            value = lighting.effectSpeed.toFloat(),
            onValueChange = { viewModel.setEffectSpeed(it.toInt()) },
            valueRange = 0f..100f
        )
    }
    SectionCard(title = "LED Direction") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (lighting.isDirectionReversed) "Reversed (LED 6 → 1)" else "Forward (LED 1 → 6)",
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = lighting.isDirectionReversed,
                onCheckedChange = { viewModel.setLedDirection(if (it) 1 else 0) }
            )
        }
    }
}

/** RPM bar tab: per-LED thresholds, warning RPM/pattern/color, hysteresis note. */
@Composable
fun RpmBarPanel(
    viewModel: LightingViewModel,
    config: ShiftLightConfig
) {
    SectionCard(
        title = "Per-LED RPM Thresholds",
        subtitle = "Each LED lights above its threshold; hysteresis prevents flicker on downshifts"
    ) {
        config.thresholds.forEachIndexed { index, rpm ->
            Column {
                Row {
                    Text("LED ${index + 1}", style = PulseShiftMonoSmall, modifier = Modifier.width(64.dp))
                    Text("$rpm RPM", style = PulseShiftMonoSmall)
                }
                Slider(
                    value = rpm.toFloat(),
                    onValueChange = { viewModel.updateThreshold(index, it.toInt()) },
                    valueRange = 500f..12000f
                )
            }
        }
        if (!config.isStrictlyAscending()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Thresholds must strictly ascend and the warning RPM must exceed the last threshold.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
    WarningCard(viewModel, config)
}

@Composable
private fun WarningCard(viewModel: LightingViewModel, config: ShiftLightConfig) {
    var warningRpm by remember(config.warningRpm) { mutableFloatStateOf(config.warningRpm.toFloat()) }
    var selectedStyle by remember(config.warningStyle) { mutableStateOf(config.warningStyle) }
    val warningColor = config.colors.getOrNull(5) ?: RgbColor.RacingRed

    SectionCard(title = "Shift Warning") {
        Row {
            Text("Warning RPM", style = PulseShiftMonoSmall, modifier = Modifier.width(110.dp))
            Text("${warningRpm.toInt()} RPM", style = PulseShiftMonoSmall)
        }
        Slider(
            value = warningRpm,
            onValueChange = { warningRpm = it },
            onValueChangeFinished = {
                viewModel.setShiftWarning(warningRpm.toInt(), selectedStyle, warningColor)
            },
            valueRange = 1000f..14000f
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WarningStyle.entries.forEach { style ->
                FilterChip(
                    selected = selectedStyle == style,
                    onClick = {
                        selectedStyle = style
                        viewModel.setShiftWarning(warningRpm.toInt(), style, warningColor)
                    },
                    label = { Text(style.label) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        HsvColorWheel(
            selectedColor = warningColor,
            onColorChanged = { color ->
                viewModel.setShiftWarning(warningRpm.toInt(), selectedStyle, color)
            }
        )
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
    SectionCard(title = "Power-on Animation") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StartupAnimation.entries.forEach { animation ->
                FilterChip(
                    selected = lighting.startupAnimation == animation,
                    onClick = {
                        viewModel.setStartupAnimation(animation, durationMs.toInt())
                    },
                    label = {
                        Column {
                            Text("${animation.displayName} — ${animation.subtitle}")
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
        Spacer(Modifier.height(12.dp))
        Row {
            Text("Duration", style = PulseShiftMonoSmall, modifier = Modifier.width(110.dp))
            Text("${(durationMs / 1000).format1()} s", style = PulseShiftMonoSmall)
        }
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

private fun Float.format1(): String = "%.1f".format(this)
