package com.pulseshift.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    showOnboarding: Boolean = false,
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
    // Keep the graph stable when DataStore emits completion and when Android restores this task.
    val startDestination = rememberSaveable {
        if (showOnboarding) Screen.Onboarding.route else Screen.Home.route
    }
    val completeOnboarding by rememberUpdatedState(onCompleteOnboarding)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val connectionState by homeViewModel.connectionState.collectAsState()
    val isAuthenticated by homeViewModel.isAuthenticated.collectAsState()

    val isTab = Screen.isBottomTab(currentRoute)

    Scaffold(
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (isTab) {
                PulseShiftTopBar(
                    connectionState = connectionState,
                    isAuthenticated = isAuthenticated,
                    onNavigateToDashboard = { navController.selectTab(Screen.Home) },
                    onNavigateToDevices = { navController.openDetail(Screen.Devices) },
                    onNavigateToTerminal = { navController.openDetail(Screen.Terminal) },
                    onNavigateToSettings = { navController.openDetail(Screen.Settings) }
                )
            }
        },
        bottomBar = {
            if (currentRoute != null && currentRoute != Screen.Onboarding.route) {
                PulseShiftBottomBar(
                    screens = Screen.bottomNavItems,
                    currentRoute = currentRoute,
                    onNavigate = navController::selectTab
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxSize(),
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
                                navController.finishWelcome(connectDevice = true, onComplete = completeOnboarding)
                            },
                            onContinueToHome = {
                                navController.finishWelcome(connectDevice = false, onComplete = completeOnboarding)
                            },
                            stripSetup = { com.pulseshift.app.ui.screens.lighting.StripSetupPanel(settingsViewModel) },
                            onConnectForSetup = { navController.navigate(Screen.Devices.route) { launchSingleTop = true } }
                        )
                    }

                    composable(Screen.Home.route) {
                        HomeScreen(
                            viewModel = homeViewModel,
                            onNavigateToLighting = { navController.selectTab(Screen.Lighting) },
                            onNavigateToSettings = { navController.openDetail(Screen.Settings) },
                            onNavigateToDevices = { navController.openDetail(Screen.Devices) },
                            onNavigateToTerminal = { navController.openDetail(Screen.Terminal) },
                            onNavigateToPresets = { navController.selectTab(Screen.Presets) },
                            onNavigateToRpm = { navController.openDetail(Screen.Rpm) }
                        )
                    }

                    composable(Screen.Lighting.route) {
                        LightingScreen(
                            viewModel = lightingViewModel,
                            stripSetup = { com.pulseshift.app.ui.screens.lighting.StripSetupPanel(settingsViewModel) },
                            rpmEditor = { snapshot ->
                                val authorized by rpmViewModel.isAuthenticated.collectAsState()
                                com.pulseshift.app.ui.screens.rpm.RpmMappingEditor(
                                    snapshot, authorized, rpmViewModel::saveMapping, rpmViewModel::testMapping
                                )
                            },
                            onNavigateToPresets = { navController.selectTab(Screen.Presets) },
                            onNavigateBack = { navController.selectTab(Screen.Home) }
                        )
                    }

                    composable(Screen.Rpm.route) {
                        RpmScreen(
                            viewModel = rpmViewModel,
                            onNavigateBack = { navController.closeDetail(Screen.Rpm) }
                        )
                    }

                    composable(Screen.Diagnostics.route) {
                        LogsScreen(
                            viewModel = logsViewModel,
                            onNavigateToTerminal = { navController.openDetail(Screen.Terminal) }
                        )
                    }

                    composable(Screen.Presets.route) {
                        PresetsScreen(viewModel = presetsViewModel)
                    }

                    composable(Screen.Terminal.route) {
                        TerminalScreen(
                            viewModel = terminalViewModel,
                            onNavigateBack = { navController.closeDetail(Screen.Terminal) }
                        )
                    }

                    composable(Screen.Devices.route) {
                        DevicesScreen(
                            viewModel = devicesViewModel,
                            onNavigateBack = {
                                if (navController.previousBackStackEntry?.destination?.route == Screen.Onboarding.route) {
                                    navController.popBackStack()
                                } else navController.closeDetail(Screen.Devices)
                            }
                        )
                    }

                    composable(Screen.Settings.route) {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onNavigateBack = { navController.closeDetail(Screen.Settings) }
                        )
                    }
                }

                // Tab Back saves that tab's state. NavHost handles system Back on detail screens.
                BackHandler(enabled = isTab && currentRoute != Screen.Home.route) {
                    navController.selectTab(Screen.Home)
                }
            }
        }
    }
}

private fun NavHostController.finishWelcome(connectDevice: Boolean, onComplete: () -> Unit) {
    if (currentDestination?.route != Screen.Onboarding.route) return
    navigate(Screen.Home.route) {
        popUpTo(Screen.Onboarding.route) { inclusive = true }
        launchSingleTop = true
    }
    if (connectDevice) openDetail(Screen.Devices)
    onComplete()
}

private fun NavHostController.selectTab(screen: Screen) {
    val currentRoute = currentDestination?.route ?: return
    if (currentRoute == Screen.Onboarding.route || currentRoute == screen.route) return

    // Close details before saving a tab so selecting a tab always opens its main screen.
    if (!Screen.isBottomTab(currentRoute)) popBackStack()
    if (currentDestination?.route == screen.route) return

    if (screen == Screen.Home) {
        popBackStack(Screen.Home.route, inclusive = false, saveState = true)
    } else {
        navigate(screen.route) {
            popUpTo(Screen.Home.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
}

private fun NavHostController.openDetail(screen: Screen) {
    if (!Screen.isBottomTab(currentDestination?.route)) return
    navigate(screen.route) { launchSingleTop = true }
}

private fun NavHostController.closeDetail(screen: Screen) {
    if (currentDestination?.route == screen.route) popBackStack()
}
