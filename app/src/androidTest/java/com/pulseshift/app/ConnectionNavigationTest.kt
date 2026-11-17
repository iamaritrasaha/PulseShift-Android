package com.pulseshift.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.data.repository.PulseShiftRepository
import com.pulseshift.app.domain.model.OtaUpdateState
import com.pulseshift.app.ui.navigation.PulseShiftNavGraph
import com.pulseshift.app.ui.screens.devices.DevicesViewModel
import com.pulseshift.app.ui.screens.home.HomeViewModel
import com.pulseshift.app.ui.screens.lighting.LightingViewModel
import com.pulseshift.app.ui.screens.logs.LogsViewModel
import com.pulseshift.app.ui.screens.presets.PresetsViewModel
import com.pulseshift.app.ui.screens.rpm.RpmViewModel
import com.pulseshift.app.ui.screens.settings.SettingsViewModel
import com.pulseshift.app.ui.screens.terminal.TerminalViewModel
import com.pulseshift.app.ui.theme.PulseShiftTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun connectionAndAuthenticationChangesKeepTheCurrentRouteAndItsBackTarget() {
        val app = compose.activity.application as PulseShiftApplication
        val repository = object : PulseShiftRepository by app.repository {
            override val connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
            override val isAuthenticated = MutableStateFlow(false)
            override val otaUpdateState = MutableStateFlow<OtaUpdateState>(OtaUpdateState.Idle)
            override suspend fun cancelOtaUpdate(): Boolean {
                otaUpdateState.value = OtaUpdateState.Idle
                return true
            }
        }

        compose.setContent {
            PulseShiftTheme {
                PulseShiftNavGraph(
                    homeViewModel = viewModel { HomeViewModel(repository) },
                    lightingViewModel = viewModel { LightingViewModel(repository) },
                    rpmViewModel = viewModel { RpmViewModel(repository) },
                    presetsViewModel = viewModel { PresetsViewModel(repository) },
                    logsViewModel = viewModel { LogsViewModel(repository) },
                    devicesViewModel = viewModel { DevicesViewModel(app.bleManager, repository) },
                    settingsViewModel = viewModel { SettingsViewModel(app.dataStore, repository) },
                    terminalViewModel = viewModel { TerminalViewModel(app.bleManager, repository) },
                    onCompleteOnboarding = {}
                )
            }
        }

        compose.onNodeWithContentDescription("Diagnostics").performClick()
        compose.onNodeWithContentDescription("Bluetooth devices").performClick()
        compose.onNodeWithText("Hardware & Bluetooth").assertIsDisplayed()

        compose.runOnIdle {
            repository.connectionState.value = BleConnectionState.RECEIVING_TELEMETRY
            repository.isAuthenticated.value = true
        }
        compose.onNodeWithText("Hardware & Bluetooth").assertIsDisplayed()
        pressBack()
        compose.onNodeWithContentDescription("Diagnostics").assertIsSelected()
        assertBluetoothStatus("Live")

        compose.onNodeWithContentDescription("Open Settings").performClick()
        compose.runOnIdle {
            repository.connectionState.value = BleConnectionState.DISCONNECTED
            repository.isAuthenticated.value = false
            repository.otaUpdateState.value = OtaUpdateState.Reconnecting(0)
        }
        compose.onNodeWithText("Reconnecting over Bluetooth").assertIsDisplayed()
        compose.runOnIdle {
            repository.connectionState.value = BleConnectionState.RECEIVING_TELEMETRY
            repository.isAuthenticated.value = true
            repository.otaUpdateState.value = OtaUpdateState.Confirming(0)
        }
        compose.onNodeWithText("Checking the running firmware").assertIsDisplayed()
        compose.runOnIdle {
            repository.otaUpdateState.value = OtaUpdateState.Failed("The controller returned to the original slot.")
            repository.connectionState.value = BleConnectionState.DISCONNECTED
            repository.isAuthenticated.value = false
        }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Diagnostics").assertIsSelected()
        assertBluetoothStatus("Offline")
        pressBack()
        compose.onNodeWithContentDescription("Dashboard").assertIsSelected()
    }

    private fun assertBluetoothStatus(status: String) {
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, status))
            .assertIsDisplayed()
    }
}
