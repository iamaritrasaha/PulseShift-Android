package com.pulseshift.app

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PulseShiftNavigationTest {
    @get:Rule(order = 0)
    val bluetoothPermissions: GrantPermissionRule = if (Build.VERSION.SDK_INT >= 31) {
        GrantPermissionRule.grant(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    private val app: PulseShiftApplication
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PulseShiftApplication

    private var originalOnboardingComplete = false

    @Before
    fun rememberOnboardingPreference() = runBlocking {
        originalOnboardingComplete = app.dataStore.isOnboardingCompleted.first()
    }

    @After
    fun restoreOnboardingPreference() = runBlocking {
        app.dataStore.setOnboardingCompleted(originalOnboardingComplete)
    }

    @Test
    fun firstRunConnectPutsDashboardUnderDevicesAndKeepsItAfterRecreation() {
        launch(completedOnboarding = false).use { scenario ->
            compose.onNodeWithText("Connect Device").performScrollTo().performClick()
            compose.onNodeWithText("Hardware & Bluetooth").assertIsDisplayed()
            runBlocking { app.dataStore.isOnboardingCompleted.first { it } }

            scenario.recreate()
            compose.onNodeWithText("Hardware & Bluetooth").assertIsDisplayed()
            pressBack()
            assertDashboard()
            compose.onNodeWithText("Connect Device").assertDoesNotExist()

            compose.onNodeWithContentDescription("Bluetooth devices").performClick()
            compose.onNodeWithContentDescription("Back").performClick()
            assertDashboard()
        }
    }

    @Test
    fun reopeningCurrentTabsAndUsingUpAndSystemBackDoesNotStackRoutes() {
        launch().use {
            selectTab("Diagnostics")
            selectTab("Diagnostics")
            compose.onNodeWithContentDescription("Open Terminal").performClick()
            compose.onNodeWithText("Hardware Terminal").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Diagnostics").assertIsSelected()

            compose.onNodeWithContentDescription("Open Terminal").performClick()
            pressBack()
            compose.onNodeWithContentDescription("Diagnostics").assertIsSelected()
            pressBack()
            assertDashboard()

            selectTab("Lighting")
            compose.onNodeWithContentDescription("Back to Dashboard").performClick()
            assertDashboard()
            selectTab("Lighting")
            pressBack()
            assertDashboard()

            compose.onNodeWithContentDescription("Open Settings").performClick()
            selectTab("Dashboard")
            assertDashboard()
            compose.onNodeWithContentDescription("Open Settings").performClick()
            pressBack()
            assertDashboard()
        }
    }

    @Test
    fun tabSelectionSurvivesSwitchingBackRotationAndBackground() {
        launch().use { scenario ->
            selectTab("Diagnostics")
            compose.onNodeWithText("Event Logs").assertIsDisplayed().performClick()
            compose.onNodeWithText("Event Logs").assertIsSelected()
            selectTab("Presets")
            selectTab("Diagnostics")
            compose.onNodeWithText("Event Logs").assertIsSelected()
            pressBack()
            assertDashboard()
            selectTab("Diagnostics")
            compose.onNodeWithText("Event Logs").assertIsSelected()

            var initialOrientation = Configuration.ORIENTATION_UNDEFINED
            scenario.onActivity { activity ->
                initialOrientation = activity.resources.configuration.orientation
                activity.requestedOrientation =
                    if (initialOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
            }
            compose.waitUntil(10_000) {
                var rotated = false
                scenario.onActivity { activity ->
                    rotated = activity.resources.configuration.orientation != initialOrientation
                }
                rotated
            }
            compose.onNodeWithText("Event Logs").assertIsSelected()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText("Event Logs").assertIsSelected()
        }
    }

    @Test
    fun systemBackDismissesDialogAndKeyboardBeforeLeavingTheirScreens() {
        launch().use { scenario ->
            selectTab("Presets")
            compose.onNodeWithText("Defaults").performClick()
            compose.onNodeWithText("Restore Default Presets?").assertIsDisplayed()
            pressBack()
            compose.onNodeWithText("Restore Default Presets?").assertDoesNotExist()
            compose.onNodeWithContentDescription("Presets").assertIsSelected()

            compose.onNodeWithContentDescription("Open Terminal").performClick()
            val commandField = compose.onNode(hasSetTextAction())
            commandField.performClick()
            commandField.performTextInput("STATUS")
            waitForKeyboard(scenario, visible = true)
            pressBack()
            waitForKeyboard(scenario, visible = false)
            compose.onNodeWithText("Hardware Terminal").assertIsDisplayed()

            commandField.performClick()
            waitForKeyboard(scenario, visible = true)
            compose.onNodeWithContentDescription("Back").performClick()
            waitForKeyboard(scenario, visible = false)
            compose.onNodeWithText("Hardware Terminal").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithContentDescription("Presets").assertIsSelected()
        }
    }

    @Test
    fun rpmBackAndDashboardShortcutReturnToTheSameRoot() {
        launch().use {
            val dashboardList = compose.onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
            )
            dashboardList.performScrollToNode(hasText("Tuning"))
            compose.onNodeWithText("Tuning").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("RPM & Calibration").assertIsDisplayed()
            compose.onNodeWithContentDescription("Back").performClick()
            assertDashboard()
            compose.onNodeWithText("Tuning").performScrollTo().assertIsDisplayed().performClick()
            selectTab("Dashboard")
            assertDashboard()
        }
    }

    @Test
    fun headingsAndNavigationLabelsFitAndStayOperable() {
        launch(completedOnboarding = false).use {
            assertTextFits("PulseShift")
            compose.onNodeWithText("Open Dashboard").performScrollTo().performClick()
            assertDashboard()
            assertTextFits("Dash")
            assertTextFits("Lights")
            assertTextFits("Diags")
            assertTextFits("Presets", singleLine = true)
            selectTab("Presets")
            assertTextFits("Presets Library")
            compose.onNodeWithText("New").performClick()
            compose.onNodeWithText("Save Preset to Library").performScrollTo().assertIsDisplayed()
            pressBack()
            compose.onNodeWithContentDescription("Presets").assertIsSelected()
            selectTab("Diagnostics")
            assertTextFits("Diagnostics & Health")
            assertTextFits("Health", singleLine = true)
            assertTextFits("Capture", singleLine = true)
            compose.onNodeWithText("Event Logs").performClick()
            val logsList = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            logsList.performScrollToNode(hasText("Fetch Historical"))
            compose.onNodeWithText("Fetch Historical").performScrollTo().assertIsDisplayed()
            assertTextFits("Fetch Historical")
            selectTab("Dashboard")
            compose.onNodeWithContentDescription("Bluetooth devices").performClick()
            assertTextFits("Hardware & Bluetooth")
            selectTab("Dashboard")
            assertDashboard()
        }
    }

    private fun assertTextFits(text: String, singleLine: Boolean = false) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true)
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayouts ->
            assertTrue(getLayouts(layouts))
        }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { layout ->
            // Paragraph width can be the full available width. Measure the rendered lines.
            val lines = 0 until layout.lineCount
            val maxWidth = lines.maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
            val fits = maxWidth <= layout.size.width + 1f &&
                layout.multiParagraph.height <= layout.size.height + 1f &&
                (!singleLine || layout.lineCount == 1) &&
                lines.none(layout::isLineEllipsized) &&
                layout.getLineEnd(lines.last) == text.length
            assertTrue("Text does not fit: $text (${layout.size}, widest line: $maxWidth px)", fits)
        }
    }

    private fun waitForKeyboard(scenario: ActivityScenario<MainActivity>, visible: Boolean) {
        compose.waitUntil(5_000) {
            var matches = false
            scenario.onActivity { activity ->
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                matches = insets?.isVisible(WindowInsetsCompat.Type.ime()) == visible
            }
            matches
        }
    }

    private fun assertDashboard() {
        compose.onNodeWithContentDescription("Dashboard").assertIsDisplayed().assertIsSelected()
    }

    private fun selectTab(name: String) {
        compose.onNodeWithContentDescription(name).assertIsDisplayed().performClick()
    }

    private fun launch(completedOnboarding: Boolean = true): ActivityScenario<MainActivity> {
        runBlocking { app.dataStore.setOnboardingCompleted(completedOnboarding) }
        return ActivityScenario.launch(MainActivity::class.java)
    }
}
