package com.pulseshift.app

import com.pulseshift.app.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {

    @Test
    fun testScreenCompanionBottomNavItemsNotNull() {
        val items = Screen.bottomNavItems
        assertNotNull(items)
        assertEquals(5, items.size)
        items.forEach { screen ->
            assertNotNull("Screen in bottomNavItems must not be null", screen)
            assertNotNull("Screen route must not be null", screen.route)
            assertTrue("Screen route must not be empty", screen.route.isNotEmpty())
        }
    }

    @Test
    fun testScreenRoutes() {
        assertEquals("onboarding", Screen.Onboarding.route)
        assertEquals("home", Screen.Home.route)
        assertEquals("lighting", Screen.Lighting.route)
        assertEquals("presets", Screen.Presets.route)
        assertEquals("logs", Screen.Logs.route)
        assertEquals("devices", Screen.Devices.route)
        assertEquals("settings", Screen.Settings.route)
    }
}
