package com.pulseshift.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pulseshift.app.data.ble.PulseShiftBleProtocol
import com.pulseshift.app.domain.model.LiveTelemetrySnapshot
import com.pulseshift.app.ui.screens.home.BatteryVoltageCard
import com.pulseshift.app.ui.theme.PulseShiftTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveVehicleDataRenderingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun liveVoltageAndRawByteFitAtCompactWidthInBothThemesAndFontScales() {
        val packet = ByteArray(20).apply {
            this[0] = 1
            this[18] = 0x7A
            this[19] = 0xA5.toByte()
        }
        val rpm = PulseShiftBleProtocol.parseRpmTelemetry(packet)!!
        val state = LiveTelemetrySnapshot.from(rpm, null, true, rpm.timestampReceivedMs)
        val configuration = mutableStateOf(false to 1f)
        compose.setContent {
            val (dark, scale) = configuration.value
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                PulseShiftTheme(darkTheme = dark) {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        BatteryVoltageCard(state, true)
                    }
                }
            }
        }
        for (dark in listOf(false, true)) {
            for (scale in listOf(1f, 2f)) {
                compose.runOnIdle { configuration.value = dark to scale }
                for (label in listOf(
                    "Battery Voltage", "12.2 V",
                    "CAN 0x303 · Byte 1 · Raw 0x7A", "Live engine battery telemetry"
                )) {
                    val node = compose.onNodeWithText(label, useUnmergedTree = true)
                    node.performScrollTo().assertIsDisplayed()
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
                        assertTrue(get(layouts))
                    }
                    assertTrue(layouts.isNotEmpty())
                    layouts.forEach { layout ->
                        val lines = 0 until layout.lineCount
                        val width = lines.maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
                        val fits = width <= layout.size.width + 1f &&
                            layout.multiParagraph.height <= layout.size.height + 1f &&
                            lines.none(layout::isLineEllipsized) &&
                            layout.getLineEnd(lines.last) == label.length
                        assertTrue(
                            "Clipped text at $scale, dark=$dark: $label, size=${layout.size}, width=$width",
                            fits
                        )
                    }
                }
            }
        }
    }
}
