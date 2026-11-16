package com.pulseshift.app.ui.screens.lighting

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.ui.components.ConfirmDialog
import com.pulseshift.app.ui.components.LedStripPreview
import com.pulseshift.app.ui.components.SectionCard
import com.pulseshift.app.ui.components.StatusPill
import com.pulseshift.app.ui.theme.HardwareAmberWarning
import com.pulseshift.app.ui.theme.HardwareGreenVerified
import com.pulseshift.app.ui.theme.PulseShiftMonoSmall
import com.pulseshift.app.ui.theme.PulseShiftMonoTiny
import com.pulseshift.app.ui.theme.PulseShiftShapeTokens
import com.pulseshift.app.ui.theme.PulseShiftSpacing

data class LightingTabItem(
    val title: String,
    val icon: ImageVector
)

private val LIGHTING_TABS = listOf(
    LightingTabItem("Colors", Icons.Rounded.Palette),
    LightingTabItem("Effects", Icons.Rounded.AutoAwesome),
    LightingTabItem("RPM Bar", Icons.Rounded.Speed),
    LightingTabItem("Startup", Icons.Rounded.PowerSettingsNew)
)

/**
 * Lighting Studio: ultra-clean, automotive cockpit illumination tuner.
 * Displays interactive live WS2812B rail preview, segmented pixel selector,
 * modern pill tabs, and explicit controller flash commit actions.
 */
@Composable
fun LightingScreen(
    viewModel: LightingViewModel,
    onNavigateToPresets: () -> Unit,
    onNavigateBack: () -> Unit = {}
) {
    val lighting by viewModel.lightingState.collectAsState()
    val shiftConfig by viewModel.shiftLightConfig.collectAsState()
    val snapshot by viewModel.configSnapshot.collectAsState()
    val unsaved by viewModel.hasUnsavedChanges.collectAsState()
    val isNvsConfirmed by viewModel.isNvsConfirmed.collectAsState()
    val selectedLed by viewModel.selectedLedIndex.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()
    val syncErrors = viewModel.lightingSyncError
    val feedbackMessages = viewModel.actionFeedback

    var tab by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }

    BackHandler {
        onNavigateBack()
    }

    LaunchedEffect(Unit) {
        syncErrors.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        feedbackMessages.collect { snackbar.showSnackbar(it) }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = "Restore firmware defaults?",
            text = "Lighting preferences will return to firmware factory defaults (1,000–6,000 RPM profile, 100% brightness). Owner pairing credentials and CAN logs are strictly preserved.",
            confirmLabel = "Restore Defaults",
            onConfirm = {
                confirmReset = false
                viewModel.restoreControllerDefaults()
            },
            onDismiss = { confirmReset = false }
        )
    }

    if (showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            shape = PulseShiftShapeTokens.Modal,
            title = { Text("Save Current Setup as Preset", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Saves all current colors, effect, speed, brightness, direction, startup animation, and RPM thresholds into your local Preset library.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(PulseShiftSpacing.RelatedControls))
                    OutlinedTextField(
                        value = newPresetName,
                        onValueChange = { newPresetName = it },
                        label = { Text("Preset Name") },
                        singleLine = true,
                        shape = PulseShiftShapeTokens.Control,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPresetName.isNotBlank()) {
                            viewModel.saveCurrentSetupAsPreset(newPresetName.trim())
                            showSavePresetDialog = false
                            newPresetName = ""
                        }
                    },
                    enabled = newPresetName.isNotBlank()
                ) {
                    Text("Save Preset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = PulseShiftSpacing.ScreenHorizontal,
                end = PulseShiftSpacing.ScreenHorizontal,
                top = PulseShiftSpacing.ScreenVertical,
                bottom = PulseShiftSpacing.BottomScrollPadding
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Header & Live Controller Status
            item {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = onNavigateBack
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "Back to Dashboard"
                                )
                            }
                            Spacer(Modifier.width(PulseShiftSpacing.Micro))
                            Column {
                                Text(
                                    "Lighting Studio",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = snapshot?.let {
                                        "Active Rev ${it.activeRevision} · Calibration " +
                                                if (it.calibrationVerified) "VERIFIED" else "UNVERIFIED"
                                    } ?: "Hardware synchronized",
                                    style = PulseShiftMonoTiny,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusPill(
                                text = when {
                                    !isAuthenticated -> "UNAUTHENTICATED"
                                    unsaved -> "RAM ONLY (UNSAVED)"
                                    isNvsConfirmed -> "PERSISTED (NVS)"
                                    else -> "RAM ACTIVE (UNSAVED)"
                                },
                                color = when {
                                    !isAuthenticated || unsaved || !isNvsConfirmed -> HardwareAmberWarning
                                    else -> HardwareGreenVerified
                                }
                            )
                            if (isAuthenticated) {
                                StatusPill(text = "AUTH OK", color = HardwareGreenVerified)
                            }
                        }
                    }
                }
            }

            // 2. Interactive LED Rail Preview Card with Segmented LED Selector
            item {
                Surface(
                    shape = PulseShiftShapeTokens.Card,
                    color = Color(0xFF0F0E17),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LedStripPreview(
                            colors = lighting.ledColors,
                            isPowerOn = lighting.isPowerOn,
                            brightness = lighting.brightness,
                            selectedIndex = selectedLed,
                            onSelectLed = { index ->
                                if (selectedLed == index) viewModel.selectLed(-1)
                                else viewModel.selectLed(index)
                            }
                        )

                        Spacer(Modifier.height(10.dp))

                        // Segmented LED Selector Bar: [ALL 6] [1] [2] [3] [4] [5] [6]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // "ALL" Chip
                            val isAllSelected = (selectedLed == -1)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isAllSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                border = if (isAllSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                                         else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                                modifier = Modifier
                                    .weight(1.4f)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { viewModel.selectLed(-1) }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "ALL 6",
                                        style = PulseShiftMonoTiny,
                                        fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isAllSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Individual LED 1..6 Chips with Color Dot
                            for (i in 0 until 6) {
                                val isSelected = (selectedLed == i)
                                val ledColor = lighting.ledColors.getOrElse(i) { RgbColor.NeonGreen }
                                val composeColor = Color(ledColor.red, ledColor.green, ledColor.blue)

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                    border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                                             else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            if (selectedLed == i) viewModel.selectLed(-1)
                                            else viewModel.selectLed(i)
                                        }
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(composeColor)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            "${i + 1}",
                                            style = PulseShiftMonoTiny,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Segmented Pill Tab Bar with Icons
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        LIGHTING_TABS.forEachIndexed { index, item ->
                            val isSelected = (tab == index)
                            val animatedColor by animateColorAsState(
                                targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                animationSpec = tween(durationMillis = 200),
                                label = "tabBg"
                            )
                            val contentColor by animateColorAsState(
                                targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                animationSpec = tween(durationMillis = 200),
                                label = "tabFg"
                            )

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = animatedColor,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { tab = index }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.title,
                                        tint = contentColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = contentColor
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. Tab Content Panel
            item {
                when (tab) {
                    0 -> ColorsPanel(viewModel, lighting, selectedLed)
                    1 -> EffectsPanel(viewModel, lighting)
                    2 -> RpmBarPanel(viewModel, shiftConfig)
                    else -> StartupPanel(viewModel, lighting)
                }
            }

            // 5. Hardware Actions Section Card
            item {
                Surface(
                    shape = PulseShiftShapeTokens.Card,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(PulseShiftSpacing.CardPadding),
                        verticalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Controller Persistence",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            StatusPill(
                                text = when {
                                    !isAuthenticated -> "AUTH REQUIRED"
                                    unsaved -> "RAM ACTIVE (UNSAVED)"
                                    isNvsConfirmed -> "SAVED IN FLASH"
                                    else -> "RAM ONLY (UNSAVED)"
                                },
                                color = when {
                                    !isAuthenticated || unsaved || !isNvsConfirmed -> HardwareAmberWarning
                                    else -> HardwareGreenVerified
                                }
                            )
                        }

                        // Primary Save to NVS Action Button
                        Button(
                            onClick = { viewModel.saveToController() },
                            enabled = !isBusy && isAuthenticated,
                            shape = PulseShiftShapeTokens.Control,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(PulseShiftSpacing.MinTouchTarget)
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(PulseShiftSpacing.SmallLabel))
                                Text("Writing to ESP32 Flash…")
                            } else {
                                Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(PulseShiftSpacing.SmallLabel))
                                Text(
                                    when {
                                        !isAuthenticated -> "Authentication Required to Save"
                                        unsaved || !isNvsConfirmed -> "Save Changes to ESP32 Flash (NVS)"
                                        else -> "All Saved to Flash — Tap to Re-Commit"
                                    },
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Secondary Grid Actions
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = {
                                    newPresetName = "Custom Preset ${System.currentTimeMillis() % 1000}"
                                    showSavePresetDialog = true
                                },
                                shape = PulseShiftShapeTokens.Control,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text("Save as Preset", maxLines = 1)
                            }

                            OutlinedButton(
                                onClick = onNavigateToPresets,
                                shape = PulseShiftShapeTokens.Control,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Icon(Icons.Rounded.Bookmarks, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Preset Library", maxLines = 1)
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(PulseShiftSpacing.RelatedControls),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.reloadFromController() },
                                shape = PulseShiftShapeTokens.Control,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Reload Flash", maxLines = 1)
                            }

                            OutlinedButton(
                                onClick = { confirmReset = true },
                                shape = PulseShiftShapeTokens.Control,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Defaults", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }
}
