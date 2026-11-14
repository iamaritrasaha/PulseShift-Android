package com.pulseshift.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pulseshift.app.ui.components.PulseShiftBottomBar
import com.pulseshift.app.ui.components.PulseShiftTopBar
import com.pulseshift.app.ui.screens.devices.DevicesScreen
import com.pulseshift.app.ui.screens.devices.DevicesViewModel
import com.pulseshift.app.ui.screens.home.HomeScreen
import com.pulseshift.app.ui.screens.home.HomeViewModel
import com.pulseshift.app.ui.screens.lighting.LightingScreen
import com.pulseshift.app.ui.screens.lighting.LightingViewModel
import com.pulseshift.app.ui.screens.logs.LogsScreen
import com.pulseshift.app.ui.screens.logs.LogsViewModel
import com.pulseshift.app.ui.screens.onboarding.OnboardingScreen
import com.pulseshift.app.ui.screens.presets.PresetsScreen
import com.pulseshift.app.ui.screens.presets.PresetsViewModel
import com.pulseshift.app.ui.screens.rpm.RpmScreen
import com.pulseshift.app.ui.screens.rpm.RpmViewModel
import com.pulseshift.app.ui.screens.settings.SettingsScreen
import com.pulseshift.app.ui.screens.settings.SettingsViewModel
import com.pulseshift.app.ui.screens.terminal.TerminalScreen
import com.pulseshift.app.ui.screens.terminal.TerminalViewModel

@Composable
fun PulseShiftNavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Home.route,
    homeViewModel: HomeViewModel,
    lightingViewModel: LightingViewModel,
    rpmViewModel: RpmViewModel,
    presetsViewModel: PresetsViewModel,
    logsViewModel: LogsViewModel,
    devicesViewModel: DevicesViewModel,
    settingsViewModel: SettingsViewModel,
    terminalViewModel: TerminalViewModel,
    onCompleteOnboarding: () -> Unit
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val connectionState by homeViewModel.connectionState.collectAsState()
    val isAuthenticated by homeViewModel.isAuthenticated.collectAsState()
    val connectedDevice by homeViewModel.connectedDevice.collectAsState()

    val isPrimaryScreen = currentRoute in Screen.bottomNavItems.map { it.route }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (isPrimaryScreen) {
                PulseShiftTopBar(
                    connectionState = connectionState,
                    isAuthenticated = isAuthenticated,
                    deviceName = connectedDevice?.name,
                    onNavigateToDevices = { navController.navigate(Screen.Devices.route) },
                    onNavigateToTerminal = { navController.navigate(Screen.Terminal.route) },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
                )
            }
        },
        bottomBar = {
            if (isPrimaryScreen) {
                PulseShiftBottomBar(
                    screens = Screen.bottomNavItems,
                    currentRoute = currentRoute,
                    onNavigate = { screen ->
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding()
                ),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier.fillMaxSize(),
                    // Snap transitions: the default crossfade can freeze
                    // mid-flight when a new navigate() interrupts a running
                    // transition, leaving two destinations composed at once and
                    // the UI stranded on the old screen until restart. An
                    // atomic swap has no animation to wedge and matches the
                    // crisp automotive instrument feel.
                    enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None }
                ) {
                    composable(Screen.Onboarding.route) {
                        OnboardingScreen(
                            onConnectDevice = {
                                onCompleteOnboarding()
                                navController.navigate(Screen.Devices.route) {
                                    popUpTo(Screen.Onboarding.route) { inclusive = true }
                                }
                            },
                            onContinueToHome = {
                                onCompleteOnboarding()
                                navController.navigate(Screen.Home.route) {
                                    popUpTo(Screen.Onboarding.route) { inclusive = true }
                                }
                            }
                        )
                    }

                    composable(Screen.Home.route) {
                        HomeScreen(
                            viewModel = homeViewModel,
                            onNavigateToLighting = { navController.navigate(Screen.Lighting.route) },
                            onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                            onNavigateToDevices = { navController.navigate(Screen.Devices.route) },
                            onNavigateToTerminal = { navController.navigate(Screen.Terminal.route) },
                            onNavigateToPresets = { navController.navigate(Screen.Presets.route) },
                            onNavigateToRpm = { navController.navigate(Screen.Rpm.route) }
                        )
                    }

                    composable(Screen.Lighting.route) {
                        LightingScreen(
                            viewModel = lightingViewModel,
                            onNavigateToPresets = { navController.navigate(Screen.Presets.route) }
                        )
                    }

                    composable(Screen.Rpm.route) {
                        RpmScreen(
                            viewModel = rpmViewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    composable(Screen.Diagnostics.route) {
                        LogsScreen(
                            viewModel = logsViewModel,
                            onNavigateToTerminal = { navController.navigate(Screen.Terminal.route) }
                        )
                    }

                    composable(Screen.Presets.route) {
                        PresetsScreen(
                            viewModel = presetsViewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    composable(Screen.Terminal.route) {
                        TerminalScreen(
                            viewModel = terminalViewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    composable(Screen.Devices.route) {
                        DevicesScreen(
                            viewModel = devicesViewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }

                    composable(Screen.Settings.route) {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onNavigateBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
