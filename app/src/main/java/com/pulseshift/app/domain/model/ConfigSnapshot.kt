package com.pulseshift.app.domain.model

data class ConfigSnapshot(
    val snapshotId: Int = 0,
    val schemaVersion: Int = 2,
    val activeRevision: Long = 0L,
    val isPowerOn: Boolean = true,
    val brightness: Int = 200,
    val globalColor: RgbColor = RgbColor.BrandViolet,
    val ledColors: List<RgbColor> = List(6) { RgbColor.BrandViolet },
    val effect: EffectType = EffectType.STATIC,
    val effectSpeed: Int = 50,
    val ledDirection: Int = 0, // 0 = Normal/LTR, 1 = Reverse/RTL
    val startupAnimation: StartupAnimation = StartupAnimation.SWEEP,
    val startupDurationMs: Int = 2000,
    val rpmThresholds: List<Int> = listOf(3000, 4000, 5000, 6000, 7000, 8000),
    val warningRpm: Int = 8500,
    val warningPatternId: Int = 0, // 0 = Flash, 1 = Strobe, 2 = Solid, 3 = Off
    val warningColor: RgbColor = RgbColor.RacingRed,
    val rpmMultiplier: Float = 40.0f,
    val canCandidateId: Long = 0x301L,
    val candidateByteIndex: Int = 0,
    val isExtendedFrame: Boolean = false,
    // --- Schema-3 additions (decoder extension + hysteresis + calibration) ---
    val rpmHysteresis: Int = 150,
    val candidateByteWidth: Int = 1,
    val candidateLittleEndian: Boolean = true,
    val rpmOffset: Float = 0.0f,
    val rpmMaxValid: Int = 12000,
    val calibrationVerified: Boolean = false,
    val isValid: Boolean = true
)
