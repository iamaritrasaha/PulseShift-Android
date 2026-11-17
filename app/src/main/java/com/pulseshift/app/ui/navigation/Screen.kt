package com.pulseshift.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector? = null) {
    data object Onboarding : Screen("onboarding", "Welcome")
    data object Home : Screen("home", "Dashboard", Icons.Rounded.Home)
    data object Lighting : Screen("lighting", "Lighting", Icons.Rounded.Lightbulb)
    data object Rpm : Screen("rpm", "RPM", Icons.Rounded.Speed)
    data object Diagnostics : Screen("diagnostics", "Diagnostics", Icons.AutoMirrored.Rounded.ListAlt)
    data object Devices : Screen("devices", "Devices", Icons.Rounded.Bluetooth)

    data object Presets : Screen("presets", "Presets", Icons.Rounded.Bookmarks)
    data object Terminal : Screen("terminal", "Terminal", Icons.Rounded.Terminal)
    data object Settings : Screen("settings", "Settings", Icons.Rounded.Settings)

    companion object {
        val bottomNavItems: List<Screen> by lazy { listOf(Home, Lighting, Presets, Diagnostics) }

        fun isBottomTab(route: String?): Boolean = bottomNavItems.any { it.route == route }
    }
}
