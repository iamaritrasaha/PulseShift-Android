package com.pulseshift.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pulseshift.app.ui.navigation.PulseShiftNavGraph
import com.pulseshift.app.ui.screens.devices.DevicesViewModel
import com.pulseshift.app.ui.screens.home.HomeViewModel
import com.pulseshift.app.ui.screens.lighting.LightingViewModel
import com.pulseshift.app.ui.screens.logs.LogsViewModel
import com.pulseshift.app.ui.screens.presets.PresetsViewModel
import com.pulseshift.app.ui.screens.settings.SettingsViewModel
import com.pulseshift.app.ui.theme.PulseShiftTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Denials surface through the BLE manager's PERMISSION_REQUIRED state
        // and the Devices screen explains what to grant; no demo mode exists.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (savedInstanceState == null) checkAndRequestBlePermissions()

        val app = application as PulseShiftApplication

        setContent {
            val scope = rememberCoroutineScope()
            val themeMode by app.dataStore.themeMode.collectAsState(initial = 0)
            val dynamicColor by app.dataStore.dynamicColor.collectAsState(initial = true)
            val isOnboardingCompleted by app.dataStore.isOnboardingCompleted.collectAsState(initial = null)

            val isDarkTheme = when (themeMode) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }

                PulseShiftTheme(darkTheme = isDarkTheme, dynamicColor = dynamicColor) {
                    val homeViewModel: HomeViewModel = viewModel { HomeViewModel(app.repository) }
                    val lightingViewModel: LightingViewModel = viewModel { LightingViewModel(app.repository) }
                    val rpmViewModel: com.pulseshift.app.ui.screens.rpm.RpmViewModel = viewModel { com.pulseshift.app.ui.screens.rpm.RpmViewModel(app.repository) }
                    val presetsViewModel: PresetsViewModel = viewModel { PresetsViewModel(app.repository) }
                    val logsViewModel: LogsViewModel = viewModel { LogsViewModel(app.repository) }
                    val devicesViewModel: DevicesViewModel = viewModel { DevicesViewModel(app.bleManager, app.repository) }
                    val settingsViewModel: SettingsViewModel = viewModel { SettingsViewModel(app.dataStore, app.repository) }
                    val terminalViewModel: com.pulseshift.app.ui.screens.terminal.TerminalViewModel =
                        viewModel { com.pulseshift.app.ui.screens.terminal.TerminalViewModel(app.bleManager, app.repository) }

                    isOnboardingCompleted?.let { completed ->
                        PulseShiftNavGraph(
                            showOnboarding = !completed,
                            homeViewModel = homeViewModel,
                            lightingViewModel = lightingViewModel,
                            rpmViewModel = rpmViewModel,
                            presetsViewModel = presetsViewModel,
                            logsViewModel = logsViewModel,
                            devicesViewModel = devicesViewModel,
                            settingsViewModel = settingsViewModel,
                            terminalViewModel = terminalViewModel,
                            onCompleteOnboarding = {
                                scope.launch {
                                    app.dataStore.setOnboardingCompleted(true)
                                }
                            }
                        )
                    }
                }
        }
    }

    private fun checkAndRequestBlePermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}
