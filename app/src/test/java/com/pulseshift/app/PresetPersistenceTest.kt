@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.pulseshift.app

import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.data.ble.PulseShiftBleProtocol
import com.pulseshift.app.data.local.PulseShiftDataStore
import com.pulseshift.app.data.repository.PulseShiftRepository
import com.pulseshift.app.domain.model.AuthState
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.CalibrationFitResult
import com.pulseshift.app.domain.model.CalibrationSessionStatus
import com.pulseshift.app.domain.model.CanHealthState
import com.pulseshift.app.domain.model.CaptureExportState
import com.pulseshift.app.domain.model.ConfigAckResponse
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.DeviceCommandOutcome
import com.pulseshift.app.domain.model.DiagnosticLogMessage
import com.pulseshift.app.domain.model.DiagnosticsReport
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.HistoricalLogLoadingState
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.OtaUpdateState
import com.pulseshift.app.domain.model.Preset
import com.pulseshift.app.domain.model.PresetApplyResult
import com.pulseshift.app.domain.model.ProfileApplyResult
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.RpmTelemetryState
import com.pulseshift.app.domain.model.ShiftLightConfig
import com.pulseshift.app.domain.model.StartupAnimation
import com.pulseshift.app.domain.model.WarningStyle
import com.pulseshift.app.ui.screens.lighting.LightingViewModel
import com.pulseshift.app.ui.screens.presets.PresetsViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PresetPersistenceTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testDefaultPresetsContain1kTo6kTachometerWithProvisionalPreview() {
        val defaults = Preset.DefaultPresets
        assertTrue("Default presets should contain at least 6 profiles", defaults.size >= 6)

        val tachometerPreset = defaults.firstOrNull { it.id == "preset_brand" }
        assertNotNull("Tachometer preset 'preset_brand' must exist in defaults", tachometerPreset)
        assertEquals("PulseShift Tachometer (1k-6k)", tachometerPreset!!.name)
        // Built-in 1k-6k tachometer preset uses provisional effect 5 (PROVISIONAL_PREVIEW) by default
        assertEquals(EffectType.PROVISIONAL_PREVIEW, tachometerPreset.effect)
        assertEquals(255, tachometerPreset.brightness)
        assertEquals(6, tachometerPreset.ledColors.size)

        val shiftConfig = tachometerPreset.shiftLightConfig
        assertNotNull("Tachometer preset must include ShiftLightConfig", shiftConfig)
        // Default LED thresholds remain [1000, 2000, 3000, 4000, 5000, 6000]
        assertEquals(listOf(1000, 2000, 3000, 4000, 5000, 6000), shiftConfig!!.thresholds)
        // Default warning threshold remains 6500 RPM
        assertEquals(6500, shiftConfig.warningRpm)
        assertEquals(WarningStyle.FLASH, shiftConfig.warningStyle)
        assertTrue(shiftConfig.isStrictlyAscending())
    }

    @Test
    fun testAllDefaultPresetsHaveBrightness255() {
        val defaults = Preset.DefaultPresets
        assertTrue("Shipped presets must contain at least 6 profiles", defaults.size >= 6)
        defaults.forEach { preset ->
            assertEquals(
                "Preset '${preset.name}' (${preset.id}) must default to 255 (100% brightness)",
                255,
                preset.brightness
            )
            assertNotNull(preset.id)
            assertTrue(preset.id.isNotBlank())
            assertNotNull(preset.name)
            assertTrue(preset.name.isNotBlank())
            assertEquals(6, preset.ledColors.size)
            assertTrue(preset.effectSpeed in 0..100)
            assertFalse(preset.isCustom)
            preset.shiftLightConfig?.let { cfg ->
                assertEquals(6, cfg.thresholds.size)
                assertTrue(cfg.isStrictlyAscending())
            }
        }
    }

    @Test
    fun testPresetDataClassDefaultsTo255Brightness() {
        val preset = Preset(
            id = "preset_test_default",
            name = "Default Brightness Test",
            effect = EffectType.STATIC,
            ledColors = List(6) { RgbColor.PureWhite }
        )
        assertEquals(
            "Preset data class default brightness parameter must be 255",
            255,
            preset.brightness
        )
    }

    @Test
    fun testDataStoreMigrationPreservesUserPresetBrightness() {
        // Construct a legacy JSON payload containing:
        // 1. User custom preset with custom brightness (140)
        // 2. User custom preset with custom brightness (190)
        // 3. User custom preset without explicit brightness field
        val legacyJson = """
            [
              {
                "id": "custom_track_1",
                "name": "Custom Track Night",
                "effectId": 0,
                "brightness": 140,
                "effectSpeed": 75,
                "ledColors": ["#FF0000","#FF0000","#FFFFFF","#FFFFFF","#FF0000","#FF0000"]
              },
              {
                "id": "custom_sunset_2",
                "name": "Dim Sunset",
                "effectId": 1,
                "brightness": 190,
                "effectSpeed": 30,
                "ledColors": ["#F97316","#F59E0B","#EF4444","#D946EF","#A855F7","#8B5CF6"]
              },
              {
                "id": "legacy_no_brightness",
                "name": "Legacy Without Brightness",
                "effectId": 2,
                "effectSpeed": 50,
                "ledColors": ["#06B6D4","#10B981","#34D399","#6366F1","#8B5CF6","#A855F7"]
              }
            ]
        """.trimIndent()

        val migratedJson = PulseShiftDataStore.migrateCustomPresetsJson(legacyJson)
        assertNotNull("Migrated JSON must not be null", migratedJson)
        assertTrue("Migrated JSON must not be blank", migratedJson.isNotBlank())

        // Verify Preset 1: custom_track_1 explicitly retained 140
        assertTrue("Preset 1 must retain its 140 brightness", Regex("\"brightness\"\\s*:\\s*140").containsMatchIn(migratedJson))
        // Verify Preset 2: custom_sunset_2 explicitly retained 190
        assertTrue("Preset 2 must retain its 190 brightness", Regex("\"brightness\"\\s*:\\s*190").containsMatchIn(migratedJson))
        // Verify Preset 3: legacy_no_brightness received default 255
        assertTrue("Preset 3 without brightness must default to 255", Regex("\"brightness\"\\s*:\\s*255").containsMatchIn(migratedJson))

        // Verify Direction and Startup defaults added
        assertTrue("Migrated JSON must contain ledDirection:0", Regex("\"ledDirection\"\\s*:\\s*0").containsMatchIn(migratedJson))
        assertTrue("Migrated JSON must contain startupAnimationId:1", Regex("\"startupAnimationId\"\\s*:\\s*1").containsMatchIn(migratedJson))
        assertTrue("Migrated JSON must contain startupDurationMs:2000", Regex("\"startupDurationMs\"\\s*:\\s*2000").containsMatchIn(migratedJson))
    }

    @Test
    fun testPresetApplicationProtocolIncludesDirectionAndStartupAnimation() {
        val seqDir = 10
        val dirPayload = PulseShiftBleProtocol.buildSetLedDirectionV2(seqDir, 1, isStaged = true)
        assertNotNull(dirPayload)
        // Direction opcode is 0x1A
        assertEquals(PulseShiftBleProtocol.V2_OPCODE_SET_LED_DIRECTION, dirPayload[1])

        val seqStartup = 11
        val startupPayload = PulseShiftBleProtocol.buildSetStartupV2(
            seqStartup,
            StartupAnimation.PULSE.id,
            durationMs = 2500,
            isStaged = true
        )
        assertNotNull(startupPayload)
        // Startup opcode is 0x15
        assertEquals(PulseShiftBleProtocol.V2_OPCODE_SET_STARTUP, startupPayload[1])
    }

    @Test
    fun testOfflinePresetOperationsWithoutBle() = runBlocking {
        val fakeRepo = FakePulseShiftRepository()
        val viewModel = PresetsViewModel(fakeRepo)

        // Verify initial state has default presets and BLE is disconnected
        assertEquals(BleConnectionState.DISCONNECTED, viewModel.connectionState.value)
        val initialCount = viewModel.presets.value.size
        assertEquals(Preset.DefaultPresets.size, initialCount)

        // 1. Create Preset Offline
        viewModel.createPreset(
            name = "Offline Dyno Profile",
            effect = EffectType.STATIC,
            colors = List(6) { RgbColor.NeonGreen },
            brightness = 255,
            speed = 60
        )

        // Verify created
        assertEquals(initialCount + 1, viewModel.presets.value.size)
        val created = viewModel.presets.value.last()
        assertEquals("Offline Dyno Profile", created.name)
        assertEquals(255, created.brightness)
        assertTrue(created.isCustom)

        // 2. Update/Rename Preset Offline
        viewModel.renamePreset(created, "Offline Dyno V2")
        val updated = viewModel.presets.value.first { it.id == created.id }
        assertEquals("Offline Dyno V2", updated.name)

        // 3. Duplicate Preset Offline
        viewModel.duplicatePreset(updated)
        assertEquals(initialCount + 2, viewModel.presets.value.size)
        val duplicate = viewModel.presets.value.last()
        assertEquals("Offline Dyno V2 (Copy)", duplicate.name)
        assertEquals(255, duplicate.brightness)

        // 4. Delete Preset Offline
        viewModel.deletePreset(created.id)
        assertFalse(viewModel.presets.value.any { it.id == created.id })

        // 5. Restore Defaults Offline
        viewModel.restoreDefaults()
        assertEquals(Preset.DefaultPresets.size, viewModel.presets.value.size)
        assertEquals(null, viewModel.persistenceError.value)
    }

    @Test
    fun testApplyPresetFailsHonestlyWhenBleFails() = runBlocking {
        val fakeRepo = FakePulseShiftRepository().apply {
            shouldFailApply = true
            applyFailureMessage = "Device does not support Config V2 protocol."
        }
        val viewModel = PresetsViewModel(fakeRepo)

        val preset = Preset.DefaultPresets.first()
        viewModel.applyPreset(preset)

        assertFalse("ViewModel must not stay busy after applyPreset failure", viewModel.isBusy.value)
    }

    @Test
    fun testPresetSnackbarDistinguishesConfirmedReadbackFromUnknownCommit() = runBlocking {
        val fakeRepo = FakePulseShiftRepository()
        val viewModel = PresetsViewModel(fakeRepo)
        val preset = Preset.DefaultPresets.first()

        for (outcome in PresetApplyResult.entries) {
            fakeRepo.presetApplyOutcome = outcome
            val feedback = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1000L) { viewModel.userMessage.first() }
            }
            viewModel.applyPreset(preset)
            val message = feedback.await()
            when (outcome) {
                PresetApplyResult.PERSISTED -> assertTrue(message.contains("saved to ESP32 NVS"))
                PresetApplyResult.READBACK_CONFIRMED -> {
                    assertTrue(message.contains("active in RAM"))
                    assertTrue(message.contains("NVS save unconfirmed"))
                }
                PresetApplyResult.APPLIED_NEEDS_READBACK -> assertTrue(message.contains("resync and save"))
                PresetApplyResult.COMMIT_UNKNOWN -> assertTrue(message.contains("commit unverified"))
            }
            assertFalse(viewModel.isBusy.value)
        }
    }

    @Test
    fun testPersistenceErrorsSurfacedToViewModel() = runBlocking {
        val fakeRepo = FakePulseShiftRepository().apply {
            shouldFailSave = true
            saveFailureMessage = "Disk I/O error writing preferences"
        }
        val viewModel = PresetsViewModel(fakeRepo)

        viewModel.createPreset(
            name = "Faulty Save",
            effect = EffectType.STATIC,
            colors = List(6) { RgbColor.PureWhite },
            brightness = 255,
            speed = 50
        )

        assertEquals("Disk I/O error writing preferences", viewModel.persistenceError.value)
    }

    @Test
    fun testDuplicatePreset() {
        val original = Preset(
            id = "preset_test",
            name = "Track Mode",
            effect = EffectType.STATIC,
            ledColors = List(6) { RgbColor.RacingRed },
            brightness = 250,
            effectSpeed = 70,
            isCustom = true,
            shiftLightConfig = ShiftLightConfig(
                thresholds = listOf(2000, 3000, 4000, 5000, 6000, 7000),
                warningRpm = 7500
            )
        )

        val duplicate = original.copy(
            id = UUID.randomUUID().toString(),
            name = "${original.name} (Copy)",
            isCustom = true,
            createdAt = System.currentTimeMillis()
        )

        assertEquals("Track Mode (Copy)", duplicate.name)
        assertFalse(duplicate.id == original.id)
        assertTrue(duplicate.isCustom)
        assertEquals(original.effect, duplicate.effect)
        assertEquals(original.brightness, duplicate.brightness)
        assertEquals(original.shiftLightConfig, duplicate.shiftLightConfig)
    }

    @Test
    fun testPresetThresholdStrictAscendingValidation() {
        // Valid 1k-6k profile
        val validConfig = ShiftLightConfig(
            thresholds = listOf(1000, 2000, 3000, 4000, 5000, 6000),
            warningRpm = 6500
        )
        assertTrue(validConfig.isStrictlyAscending())

        // Warning lower than LED 6 threshold
        val invalidWarning = ShiftLightConfig(
            thresholds = listOf(1000, 2000, 3000, 4000, 5000, 6000),
            warningRpm = 5900
        )
        assertFalse(invalidWarning.isStrictlyAscending())

        // Non-monotonic thresholds (e.g. inverted step)
        val invertedStep = ShiftLightConfig(
            thresholds = listOf(1000, 3000, 2000, 4000, 5000, 6000),
            warningRpm = 6500
        )
        assertFalse(invertedStep.isStrictlyAscending())

        // Equal thresholds
        val equalStep = ShiftLightConfig(
            thresholds = listOf(1000, 2000, 3000, 3000, 5000, 6000),
            warningRpm = 6500
        )
        assertFalse(equalStep.isStrictlyAscending())
    }

    @Test
    fun testPresetDirectionAndStartupAnimationDefaults() {
        val preset = Preset(
            id = "preset_simple",
            name = "Simple Preset",
            effect = EffectType.BREATHING,
            ledColors = List(6) { RgbColor.AmberWarning }
        )

        assertEquals(0, preset.ledDirection)
        assertEquals(StartupAnimation.SWEEP, preset.startupAnimation)
        assertEquals(2000, preset.startupDurationMs)
        assertEquals(255, preset.brightness)
    }

    @Test
    fun testProfileApplyResultDistinguishesPersistedVsAppliedOnly() {
        assertTrue("PERSISTED must report isSuccess", ProfileApplyResult.PERSISTED.isSuccess)
        assertTrue("PERSISTED must report isPersisted", ProfileApplyResult.PERSISTED.isPersisted)

        assertTrue("APPLIED_ONLY must report isSuccess", ProfileApplyResult.APPLIED_ONLY.isSuccess)
        assertFalse("APPLIED_ONLY must not report isPersisted", ProfileApplyResult.APPLIED_ONLY.isPersisted)

        assertFalse("FAILED must not report isSuccess", ProfileApplyResult.FAILED.isSuccess)
        assertFalse("FAILED must not report isPersisted", ProfileApplyResult.FAILED.isPersisted)
    }

    @Test
    fun testPersistenceRequiresBothSuccessAndPersistedFlag() {
        // 1. Success + Persisted (0x01) -> True persistence
        val ackSuccessPersisted = ConfigAckResponse(
            opcode = PulseShiftBleProtocol.V2_OPCODE_SAVE_CONFIG,
            sequenceRef = 42,
            statusCode = ConfigAckResponse.STATUS_OK.toByte(),
            resultFlags = 0x01.toByte(),
            activeRevision = 100L
        )
        assertTrue(ackSuccessPersisted.isSuccess)
        assertTrue(ackSuccessPersisted.isPersisted)
        assertTrue("Successful persistence strictly requires ACK.isSuccess && ACK.isPersisted",
            ackSuccessPersisted.isSuccess && ackSuccessPersisted.isPersisted)

        // 2. Success + Staged only / RAM only (resultFlags without bit 0) -> NOT persisted
        val ackSuccessNotPersisted = ConfigAckResponse(
            opcode = PulseShiftBleProtocol.V2_OPCODE_SAVE_CONFIG,
            sequenceRef = 43,
            statusCode = ConfigAckResponse.STATUS_OK.toByte(),
            resultFlags = 0x04.toByte(), // Applied in RAM, but NOT persisted to NVS
            activeRevision = 100L
        )
        assertTrue(ackSuccessNotPersisted.isSuccess)
        assertFalse(ackSuccessNotPersisted.isPersisted)
        assertFalse("RAM-only success must NOT evaluate as persisted",
            ackSuccessNotPersisted.isSuccess && ackSuccessNotPersisted.isPersisted)

        // 3. Storage error with bit set (invalid state) -> NOT persisted because not success
        val ackError = ConfigAckResponse(
            opcode = PulseShiftBleProtocol.V2_OPCODE_SAVE_CONFIG,
            sequenceRef = 44,
            statusCode = ConfigAckResponse.STATUS_ERR_STORAGE_ERROR.toByte(),
            resultFlags = 0x01.toByte(),
            activeRevision = 100L
        )
        assertFalse(ackError.isSuccess)
        assertFalse("Failed ACK must NOT evaluate as persisted even if resultFlags has bits",
            ackError.isSuccess && ackError.isPersisted)
    }

    @Test
    fun testLightingViewModelSurfacesExplicitProfileOutcomes() = runBlocking {
        val fakeRepo = FakePulseShiftRepository()
        val viewModel = LightingViewModel(fakeRepo)

        // 1. Persisted outcome
        fakeRepo.profileApplyOutcome = ProfileApplyResult.PERSISTED
        viewModel.apply1000To6000Profile()
        var feedback = viewModel.actionFeedback.replayCache.lastOrNull()
        assertTrue("Feedback must state applied and saved to ESP32 Flash (NVS)",
            feedback?.contains("applied and saved to ESP32 Flash (NVS)") == true)

        // 2. Applied-only outcome
        fakeRepo.profileApplyOutcome = ProfileApplyResult.APPLIED_ONLY
        viewModel.apply1000To6000Profile()
        feedback = viewModel.actionFeedback.replayCache.lastOrNull()
        assertTrue("Feedback must state active in RAM only (NVS save unconfirmed)",
            feedback?.contains("active in RAM only (NVS save unconfirmed)") == true)

        // 3. Failed outcome
        fakeRepo.profileApplyOutcome = ProfileApplyResult.FAILED
        viewModel.apply1000To6000Profile()
        feedback = viewModel.actionFeedback.replayCache.lastOrNull()
        assertTrue("Feedback must state failed to apply profile",
            feedback?.contains("Failed to apply") == true)
    }
}

/**
 * Fake PulseShiftRepository for unit testing offline preset workflows and error surfacing.
 */
class FakePulseShiftRepository : PulseShiftRepository {
    private val _presets = MutableStateFlow<List<Preset>>(Preset.DefaultPresets)
    override val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _authState = MutableStateFlow(AuthState.NOT_ENROLLED)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _lightingState = MutableStateFlow(LightingState())
    override val lightingState: StateFlow<LightingState> = _lightingState.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BleDevice?>(null)
    override val connectedDevice: StateFlow<BleDevice?> = _connectedDevice.asStateFlow()

    private val _shiftLightConfig = MutableStateFlow(ShiftLightConfig())
    override val shiftLightConfig: StateFlow<ShiftLightConfig> = _shiftLightConfig.asStateFlow()

    private val _configSnapshot = MutableStateFlow<ConfigSnapshot?>(null)
    override val configSnapshot: StateFlow<ConfigSnapshot?> = _configSnapshot.asStateFlow()

    private val _hasUnsavedChanges = MutableStateFlow(false)
    override val hasUnsavedChanges: StateFlow<Boolean> = _hasUnsavedChanges.asStateFlow()

    private val _isNvsConfirmed = MutableStateFlow(false)
    override val isNvsConfirmed: StateFlow<Boolean> = _isNvsConfirmed.asStateFlow()

    private val _lastAckResponse = MutableSharedFlow<ConfigAckResponse>()
    override val lastAckResponse: SharedFlow<ConfigAckResponse> = _lastAckResponse.asSharedFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    override val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _isReconnecting = MutableStateFlow(false)
    override val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    private val _lightingSyncError = MutableSharedFlow<String>()
    override val lightingSyncError: SharedFlow<String> = _lightingSyncError.asSharedFlow()

    private val _rpmTelemetryState = MutableStateFlow<RpmTelemetryState?>(null)
    override val rpmTelemetryState: StateFlow<RpmTelemetryState?> = _rpmTelemetryState.asStateFlow()

    private val _canHealthState = MutableStateFlow<CanHealthState?>(null)
    override val canHealthState: StateFlow<CanHealthState?> = _canHealthState.asStateFlow()

    private val _diagnosticLogMessages = MutableStateFlow<List<DiagnosticLogMessage>>(emptyList())
    override val diagnosticLogMessages: StateFlow<List<DiagnosticLogMessage>> = _diagnosticLogMessages.asStateFlow()

    private val _historicalPageLoadingState = MutableStateFlow<HistoricalLogLoadingState>(HistoricalLogLoadingState.Idle)
    override val historicalPageLoadingState: StateFlow<HistoricalLogLoadingState> = _historicalPageLoadingState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _diagnosticsReport = MutableStateFlow<DiagnosticsReport?>(null)
    override val diagnosticsReport: StateFlow<DiagnosticsReport?> = _diagnosticsReport.asStateFlow()

    private val _captureExportState = MutableStateFlow<CaptureExportState>(CaptureExportState.Idle)
    override val captureExportState: StateFlow<CaptureExportState> = _captureExportState.asStateFlow()

    private val _deviceCommandHistory = MutableStateFlow<List<DeviceCommandOutcome>>(emptyList())
    override val deviceCommandHistory: StateFlow<List<DeviceCommandOutcome>> = _deviceCommandHistory.asStateFlow()

    var shouldFailApply = false
    var applyFailureMessage = "Apply preset failed"
    var shouldFailSave = false
    var saveFailureMessage = "Save preset failed"
    var profileApplyOutcome: ProfileApplyResult = ProfileApplyResult.PERSISTED
    var presetApplyOutcome: PresetApplyResult = PresetApplyResult.READBACK_CONFIRMED

    override suspend fun enrollOwner(secret: ByteArray): Boolean = false
    override suspend fun authenticateChallengeResponse(): Boolean = false
    override suspend fun clearStoredOwnerCredential(): Boolean = false
    override suspend fun setPower(power: Boolean) {}
    override suspend fun setBrightness(brightness: Int) {}
    override suspend fun setEffect(effect: EffectType): Boolean = true
    override suspend fun setEffectSpeed(speed: Int) {}
    override suspend fun setGlobalColor(color: RgbColor) {}
    override suspend fun setIndividualLedColor(index: Int, color: RgbColor) {}
    override suspend fun setAllLedColors(colors: List<RgbColor>) {}
    override suspend fun setStartupAnimation(animation: StartupAnimation, durationMs: Int) {}
    override suspend fun setLedDirection(direction: Int) {}
    override suspend fun setWarningRgb(color: RgbColor) {}
    override suspend fun updateShiftLightConfig(config: ShiftLightConfig) {}
    override suspend fun setLedThreshold(index: Int, rpm: Int) {}
    override suspend fun applyDecoderConfiguration(canId: Long, byteIndex: Int, isExtended: Boolean, multiplier: Float) {}
    override suspend fun setRpmHysteresis(hysteresisRpm: Int) {}

    override suspend fun applyPreset(preset: Preset): PresetApplyResult {
        if (shouldFailApply) {
            throw IllegalStateException(applyFailureMessage)
        }
        _lightingState.value = _lightingState.value.copy(
            effect = preset.effect,
            brightness = preset.brightness,
            effectSpeed = preset.effectSpeed,
            ledColors = preset.ledColors,
            ledDirection = preset.ledDirection,
            startupAnimation = preset.startupAnimation,
            startupDurationMs = preset.startupDurationMs
        )
        return presetApplyOutcome
    }

    override suspend fun savePreset(preset: Preset) {
        if (shouldFailSave) {
            throw java.io.IOException(saveFailureMessage)
        }
        val list = _presets.value.toMutableList()
        val index = list.indexOfFirst { it.id == preset.id }
        if (index >= 0) {
            list[index] = preset.copy(isCustom = true)
        } else {
            val assignedId = if (preset.id.isNotBlank()) preset.id else UUID.randomUUID().toString()
            list.add(preset.copy(id = assignedId, isCustom = true))
        }
        _presets.value = list
    }

    override suspend fun updatePreset(preset: Preset) {
        if (shouldFailSave) {
            throw java.io.IOException(saveFailureMessage)
        }
        val list = _presets.value.toMutableList()
        val index = list.indexOfFirst { it.id == preset.id }
        if (index >= 0) {
            list[index] = preset.copy(isCustom = true)
            _presets.value = list
        }
    }

    override suspend fun duplicatePreset(preset: Preset): Preset? {
        if (shouldFailSave) {
            throw java.io.IOException(saveFailureMessage)
        }
        val newPreset = preset.copy(
            id = UUID.randomUUID().toString(),
            name = "${preset.name} (Copy)",
            isCustom = true,
            createdAt = System.currentTimeMillis()
        )
        val list = _presets.value.toMutableList()
        list.add(newPreset)
        _presets.value = list
        return newPreset
    }

    override suspend fun saveCurrentSetupAsPreset(name: String): Preset? = null

    override suspend fun deletePreset(id: String) {
        if (shouldFailSave) {
            throw java.io.IOException(saveFailureMessage)
        }
        val list = _presets.value.toMutableList()
        list.removeAll { it.id == id && it.isCustom }
        _presets.value = list
    }

    override suspend fun restoreDefaultPresets() {
        if (shouldFailSave) {
            throw java.io.IOException(saveFailureMessage)
        }
        _presets.value = Preset.DefaultPresets
    }

    override suspend fun apply1000To6000Profile(): ProfileApplyResult = profileApplyOutcome
    override suspend fun saveConfigurationToController(): Boolean = true
    override suspend fun reloadConfigFromController(): ConfigSnapshot? = _configSnapshot.value
    override suspend fun restoreDefaultControllerConfig() {}
    override suspend fun requestHistoricalLogPage(startOffset: Long) {}
    override suspend fun cancelHistoricalLogRetrieval() {}
    override suspend fun clearDiagnosticLogs() {}
    override fun exportLogsAsText(): String = ""
    override fun exportLogsAsCsv(): String = ""
    override suspend fun requestDiagnosticsReport(): DiagnosticsReport? = null
    override suspend fun reloadConfigurationFromDevice(): Boolean = true
    override suspend fun restartDevice(): Boolean = true
    override suspend fun clearDeviceDiagnosticHistory(): Boolean = true
    override suspend fun setCaptureRecording(enable: Boolean): Boolean = true
    override suspend fun setCaptureStage(stage: Int): Boolean = true
    override suspend fun setLedTestRpm(rpm: Int, enable: Boolean): Boolean = true
    override fun startCaptureExport(startRecord: Long) {}
    override fun cancelCaptureExport() {}
    override suspend fun queryCalibrationStatus(): CalibrationSessionStatus? = null
    override suspend fun beginCalibrationSession(): Boolean = true
    override suspend fun startCalibrationCapture(): Boolean = true
    override suspend fun commitCalibrationPoint(refRpm: Int): Boolean = true
    override suspend fun computeCalibration(apply: Boolean): CalibrationFitResult? = null
    override suspend fun abortCalibrationSession(): Boolean = true

    override val otaUpdateState: StateFlow<OtaUpdateState> = MutableStateFlow<OtaUpdateState>(OtaUpdateState.Idle).asStateFlow()
    override suspend fun startOtaUpdate(imageBytes: ByteArray): Boolean = true
    override suspend fun cancelOtaUpdate(): Boolean = true
    override suspend fun confirmOtaUpdate(): Boolean = true
}
