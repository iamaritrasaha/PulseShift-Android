package com.pulseshift.app.domain.model

data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val isConnected: Boolean = false,
    val firmwareVersion: String? = null,
    val hardwareRevision: String? = null,
    val capabilities: DeviceCapabilities = DeviceCapabilities()
)

data class DeviceCapabilities(
    val physicalLedCount: Int = 0,
    val configRevision: Long = 0L,
    val hardwarePresetCapacity: Int = 0,
    val securityMode: Int = 0,
    val firmwareMajor: Int = 0,
    val firmwareMinor: Int = 0,
    val firmwarePatch: Int = 0,
    val supportsIndividualRgb: Boolean = false,
    val supportsEffects: Boolean = false,
    val supportsStartupAnimation: Boolean = false,
    val supportsRpmThresholds: Boolean = false,
    val supportsShiftWarning: Boolean = false,
    val supportsMultiplier: Boolean = false,
    val supportsCandidateSource: Boolean = false,
    val supportsPhoneManagedPresets: Boolean = false,
    val supportsNvsPersistence: Boolean = false,
    val supportsAuthenticatedWrites: Boolean = false,
    val supportsDecoderExt: Boolean = false,
    val supportsHysteresis: Boolean = false,
    val supportsCalibrationWorkflow: Boolean = false,
    val supportsDiagnosticsTlv: Boolean = false,
    val supportsCaptureExport: Boolean = false,
    val supportsCaptureStage: Boolean = false,
    val supportsLedBenchTest: Boolean = false,
    val hasConfigV2Support: Boolean = false,
    val supportsLiveRpmVerified: Boolean = false,
    val maxStartupAnimId: Int = 3,
    val maxEffectId: Int = 5,
    val otaActiveSlot: Int = 0xFF,
    val protocolMinor: Int = 0,
    val otaFlags: Int = 0,
    val supportsExtendedStartupAnimation: Boolean = false,
    val supportsLoopingStartup: Boolean = false,
    val supportsRangeDiscovery: Boolean = false,
    val supportsOtaUpdate: Boolean = false,
    val supportsStripConfig: Boolean = false,
    val supportsVerifiedSave: Boolean = false
) {
    val hasDualOtaSlots: Boolean get() = (otaFlags and 0x02) != 0
    val supportsOtaImageDigest: Boolean get() = (otaFlags and 0x04) != 0
    val firmwareVersion: String
        get() = if (firmwareMajor > 0) "$firmwareMajor.$firmwareMinor.$firmwarePatch" else "unknown"

    val supportedStartupAnimations: Set<StartupAnimation>
        get() = StartupAnimation.entries.filter { it.id <= maxStartupAnimId }.toSet()

    companion object {
        fun fromV2(
            physicalLedCount: Int,
            featureMask: Long,
            configRevision: Long,
            hardwarePresetCapacity: Int,
            securityMode: Int,
            firmwareMajor: Int = 0,
            firmwareMinor: Int = 0,
            firmwarePatch: Int = 0,
            maxStartupAnimId: Int = 3,
            maxEffectId: Int = 5,
            otaActiveSlot: Int = 0xFF,
            protocolMinor: Int = 0,
            otaFlags: Int = 0
        ): DeviceCapabilities {
            val mask = featureMask.toInt()
            val hasExtAnim = (mask and 0x20000) != 0 || maxStartupAnimId >= 5
            val effectiveMaxAnim = if (hasExtAnim && maxStartupAnimId < 5) 5 else maxStartupAnimId
            return DeviceCapabilities(
                physicalLedCount = physicalLedCount,
                configRevision = configRevision,
                hardwarePresetCapacity = hardwarePresetCapacity,
                securityMode = securityMode,
                firmwareMajor = firmwareMajor,
                firmwareMinor = firmwareMinor,
                firmwarePatch = firmwarePatch,
                supportsIndividualRgb = (mask and 0x0001) != 0,
                supportsEffects = (mask and 0x0002) != 0,
                supportsStartupAnimation = (mask and 0x0004) != 0,
                supportsRpmThresholds = (mask and 0x0008) != 0,
                supportsShiftWarning = (mask and 0x0010) != 0,
                supportsMultiplier = (mask and 0x0020) != 0,
                supportsCandidateSource = (mask and 0x0040) != 0,
                supportsPhoneManagedPresets = (mask and 0x0080) != 0,
                supportsNvsPersistence = (mask and 0x0100) != 0,
                supportsAuthenticatedWrites = (mask and 0x0200) != 0,
                supportsDecoderExt = (mask and 0x0400) != 0,
                supportsHysteresis = (mask and 0x0800) != 0,
                supportsCalibrationWorkflow = (mask and 0x1000) != 0,
                supportsDiagnosticsTlv = (mask and 0x2000) != 0,
                supportsCaptureExport = (mask and 0x4000) != 0,
                supportsCaptureStage = (mask and 0x8000) != 0,
                supportsLedBenchTest = (mask and 0x10000) != 0,
                supportsExtendedStartupAnimation = hasExtAnim,
                supportsLoopingStartup = (mask and 0x100000) != 0,
                supportsRangeDiscovery = (mask and 0x40000) != 0,
                supportsOtaUpdate = (mask and 0x80000) != 0,
                supportsStripConfig = protocolMinor >= 4 && (mask and 0x200000) != 0,
                supportsVerifiedSave = protocolMinor >= 4 && (mask and 0x400000) != 0,
                hasConfigV2Support = true,
                supportsLiveRpmVerified = false,
                maxStartupAnimId = effectiveMaxAnim,
                maxEffectId = maxEffectId,
                otaActiveSlot = otaActiveSlot,
                protocolMinor = protocolMinor,
                otaFlags = otaFlags
            )
        }
    }
}
