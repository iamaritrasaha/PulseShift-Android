package com.pulseshift.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pulseshift.app.domain.model.AmbientPlayback
import com.pulseshift.app.domain.model.AnimationSettings
import com.pulseshift.app.domain.model.AnimationStatus
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.LightingSetup
import com.pulseshift.app.domain.model.RpmMapping
import com.pulseshift.app.domain.model.RpmMappingMode
import com.pulseshift.app.domain.model.StartupAnimation
import com.pulseshift.app.domain.model.StripConfiguration
import com.pulseshift.app.ui.screens.lighting.AnimationSetupPanel
import com.pulseshift.app.ui.screens.rpm.RpmMappingEditor
import com.pulseshift.app.ui.theme.PulseShiftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LightingSetupUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val mapping = RpmMapping(RpmMappingMode.INDIVIDUAL, 12, List(12) { 1000 + 100 * it })
    private val boot = AnimationSettings(true, StartupAnimation.SWEEP, 2000)
    private val ambient = AnimationSettings(false, StartupAnimation.WAVE, 5000)
    private val setup = LightingSetup(12, mapping, boot, ambient, 3000)
    private val snapshot = ConfigSnapshot(lightingSetup = setup, strip = StripConfiguration(12, 12, 1..64), matchesFlash = true)

    @Test
    fun editLed12AndSaveAfterStateRestoration() {
        var submitted: RpmMapping? = null
        val restore = StateRestorationTester(compose)
        restore.setContent {
            PulseShiftTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RpmMappingEditor(snapshot, true, { map, _ -> submitted = map; true }, { true })
                }
            }
        }
        compose.onNode(hasText("LED (1–12)") and hasSetTextAction()).performScrollTo().performTextReplacement("12")
        compose.onNode(hasText("LED threshold") and hasSetTextAction()).performScrollTo().performTextReplacement("2400")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Save Mapping to ESP32").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(mapping.thresholds.dropLast(1) + 2400, submitted?.thresholds)
        }
    }

    @Test
    fun continuousToggleStaysDraftUntilImmediatelySaved() {
        var submitted: Pair<AnimationSettings, AnimationSettings>? = null
        compose.setContent {
            PulseShiftTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AnimationSetupPanel(snapshot, "test", true,
                        { AnimationStatus(AmbientPlayback.OFF, true) },
                        { boot, ambient -> submitted = boot to ambient; true },
                        { true })
                }
            }
        }
        compose.onAllNodes(isToggleable())[1].performScrollTo().performClick()
        compose.onNodeWithText("Controller setting · Off").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save Animations to ESP32").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(boot, submitted?.first)
            assertTrue(submitted?.second?.enabled == true)
            assertEquals(5000, submitted?.second?.durationMs)
        }
    }
}
