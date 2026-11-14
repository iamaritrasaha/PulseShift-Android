package com.pulseshift.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
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

    val showBottomBar = currentRoute in Screen.bottomNavItems.map { it.route }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {
                    Screen.bottomNavItems.forEach { screen ->
                        val selected = currentRoute == screen.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                screen.icon?.let {
                                    Icon(it, contentDescription = screen.title)
                                }
                            },
                            label = { Text(screen.title, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())
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
                RpmScreen(viewModel = rpmViewModel)
            }

            composable(Screen.Diagnostics.route) {
                LogsScreen(viewModel = logsViewModel)
            }

            composable(Screen.Presets.route) {
                PresetsScreen(viewModel = presetsViewModel)
            }

            composable(Screen.Terminal.route) {
                TerminalScreen(viewModel = terminalViewModel)
            }

            composable(Screen.Devices.route) {
                DevicesScreen(viewModel = devicesViewModel)
            }

            composable(Screen.Settings.route) {
                SettingsScreen(viewModel = settingsViewModel)
            }
        }
    }
}
