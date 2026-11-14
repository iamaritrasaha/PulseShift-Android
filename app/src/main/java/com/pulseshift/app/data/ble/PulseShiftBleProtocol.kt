package com.pulseshift.app.data.ble

import com.pulseshift.app.domain.model.CanHealthState
import com.pulseshift.app.domain.model.DeviceCapabilities
import com.pulseshift.app.domain.model.EffectType
import com.pulseshift.app.domain.model.HealthFlags
import com.pulseshift.app.domain.model.LightingState
import com.pulseshift.app.domain.model.RgbColor
import com.pulseshift.app.domain.model.RpmStatusFlags
import com.pulseshift.app.domain.model.RpmTelemetryState
import java.security.MessageDigest
import java.util.UUID

object PulseShiftBleProtocol {
    // PulseShift BLE V1 Primary Service & Characteristics
    val SERVICE_UUID: UUID = UUID.fromString("fe12d210-3d4f-4b9d-a21c-92782ecb4000")
    val RPM_CHAR_UUID: UUID = UUID.fromString("fe12d211-3d4f-4b9d-a21c-92782ecb4000")
    val HEALTH_CHAR_UUID: UUID = UUID.fromString("fe12d212-3d4f-4b9d-a21c-92782ecb4000")
    val LOG_CHAR_UUID: UUID = UUID.fromString("fe12d213-3d4f-4b9d-a21c-92782ecb4000")
    val CONTROL_CHAR_UUID: UUID = UUID.fromString("fe12d214-3d4f-4b9d-a21c-92782ecb4000")

    // PulseShift BLE Configuration V2 Characteristics
    val CONFIG_CMD_CHAR_UUID: UUID = UUID.fromString("fe12d215-3d4f-4b9d-a21c-92782ecb4000")
    val CONFIG_RESULT_CHAR_UUID: UUID = UUID.fromString("fe12d216-3d4f-4b9d-a21c-92782ecb4000")
    val CONFIG_SNAPSHOT_CHAR_UUID: UUID = UUID.fromString("fe12d217-3d4f-4b9d-a21c-92782ecb4000")
    val CAPABILITIES_CHAR_UUID: UUID = UUID.fromString("fe12d218-3d4f-4b9d-a21c-92782ecb4000")

    // Legacy / Lighting Service UUIDs for compatibility
    val LEGACY_SERVICE_UUID: UUID = UUID.fromString("7f4bd000-6fb1-4db9-9214-3ee4e7e49210")
    val COMMAND_CHAR_UUID: UUID = UUID.fromString("7f4bd001-6fb1-4db9-9214-3ee4e7e49210")
    val STATE_CHAR_UUID: UUID = UUID.fromString("7f4bd002-6fb1-4db9-9214-3ee4e7e49210")
    val DEVICE_INFO_CHAR_UUID: UUID = UUID.fromString("7f4bd003-6fb1-4db9-9214-3ee4e7e49210")

    const val PROTOCOL_VERSION: Byte = 0x01
    const val PROTOCOL_V2_VERSION: Byte = 0x02
    const val DEVICE_NAME: String = "PulseShift"

    // Configuration V2 Opcodes (Canonical Firmware Protocol)
    const val V2_OPCODE_GET_CAPABILITIES: Byte = 0x01
    const val V2_OPCODE_GET_SNAPSHOT: Byte = 0x02
    const val V2_OPCODE_SET_POWER: Byte = 0x10
    const val V2_OPCODE_SET_BRIGHTNESS: Byte = 0x11
    const val V2_OPCODE_SET_GLOBAL_RGB: Byte = 0x12
    const val V2_OPCODE_SET_LED_RGB: Byte = 0x13
    const val V2_OPCODE_SET_EFFECT: Byte = 0x14
    const val V2_OPCODE_SET_STARTUP: Byte = 0x15
    const val V2_OPCODE_SET_LED_THRESHOLD: Byte = 0x16
    const val V2_OPCODE_SET_SHIFT_WARNING: Byte = 0x17
    const val V2_OPCODE_SET_RPM_MULTIPLIER: Byte = 0x18
    const val V2_OPCODE_SET_CANDIDATE_SOURCE: Byte = 0x19
    const val V2_OPCODE_SET_LED_DIRECTION: Byte = 0x1A
    const val V2_OPCODE_SET_WARNING_RGB: Byte = 0x1B
    const val V2_OPCODE_SET_RPM_HYSTERESIS: Byte = 0x1C
    const val V2_OPCODE_SET_RPM_DECODER_EXT: Byte = 0x1D
    const val V2_OPCODE_SAVE_CONFIG: Byte = 0x20
    const val V2_OPCODE_RESTORE_DEFAULTS: Byte = 0x21
    const val V2_OPCODE_RELOAD_CONFIG: Byte = 0x22
    const val V2_OPCODE_ENROLL_OWNER: Byte = 0x24
    const val V2_OPCODE_AUTHENTICATE: Byte = 0x25
    const val V2_OPCODE_REQUEST_CHALLENGE: Byte = 0x26
    const val V2_OPCODE_BEGIN_TRANSACTION: Byte = 0x30
    const val V2_OPCODE_COMMIT_TRANSACTION: Byte = 0x31
    const val V2_OPCODE_ABORT_TRANSACTION: Byte = 0x32

    // Mobile-assisted RPM calibration workflow (firmware schema 3)
    const val V2_OPCODE_CAL_BEGIN: Byte = 0x40
    const val V2_OPCODE_CAL_ABORT: Byte = 0x41
    const val V2_OPCODE_CAL_CAPTURE_START: Byte = 0x42
    const val V2_OPCODE_CAL_CAPTURE_STATUS: Byte = 0x43
    const val V2_OPCODE_CAL_POINT_COMMIT: Byte = 0x44
    const val V2_OPCODE_CAL_COMPUTE: Byte = 0x45
    const val V2_OPCODE_CAL_STATUS: Byte = 0x46

    // Device control & diagnostics (firmware protocol 2.2.0)
    const val V2_OPCODE_GET_DIAGNOSTICS: Byte = 0x50
    const val V2_OPCODE_RESTART_DEVICE: Byte = 0x51
    const val V2_OPCODE_CLEAR_DIAGNOSTICS: Byte = 0x52
    const val V2_OPCODE_SET_CAPTURE_RECORDING: Byte = 0x53

    // Control Request Opcodes (V1 Protocol)
    const val CONTROL_OPCODE_FETCH_HISTORICAL_LOG: Byte = 0x10
    const val CONTROL_OPCODE_CANCEL_HISTORICAL_LOG: Byte = 0x11
    const val CONTROL_OPCODE_FETCH_CAPTURE_PAGE: Byte = 0x12
    const val CONTROL_OPCODE_CANCEL_CAPTURE: Byte = 0x13

    // Lighting Opcodes (Compatibility)
    const val CMD_SET_POWER: Byte = 0x01
    const val CMD_SET_BRIGHTNESS: Byte = 0x02
    const val CMD_SET_EFFECT: Byte = 0x03
    const val CMD_SET_GLOBAL_COLOR: Byte = 0x04
    const val CMD_SET_LED_COLOR: Byte = 0x05
    const val CMD_SET_EFFECT_SPEED: Byte = 0x06
    const val CMD_SET_RPM_THRESHOLDS: Byte = 0x07
    const val CMD_SET_SHIFT_WARNING: Byte = 0x08
    const val CMD_SET_SHIFT_LED_COLOR: Byte = 0x09
    const val CMD_SET_STARTUP_CONFIG: Byte = 0x0A
    const val CMD_SAVE_TO_NVS: Byte = 0x0B
    const val CMD_REQUEST_STATE: Byte = 0x0C
    const val CMD_REQUEST_CAPABILITIES: Byte = 0x0D
    const val CMD_RESET_DEFAULTS: Byte = 0x0E

    // Status Codes (Canonical Firmware Protocol - ble_protocol.h)
    const val STATUS_SUCCESS: Byte = 0x00
    const val STATUS_ERR_INVALID_PARAM: Byte = 0x01
    const val STATUS_ERR_UNSUPPORTED: Byte = 0x02
    const val STATUS_ERR_BUSY: Byte = 0x03
    const val STATUS_ERR_UNAUTHORIZED: Byte = 0x04
    const val STATUS_ERR_INVALID_STATE: Byte = 0x05
    const val STATUS_ERR_STORAGE_ERROR: Byte = 0x06
    const val STATUS_ERR_VERSION_MISMATCH: Byte = 0x07
    const val STATUS_ERR_VALIDATION_FAILED: Byte = 0x08

    // Frame types
    const val FRAME_TYPE_ACK: Byte = 0x80.toByte()
    const val FRAME_TYPE_STATE: Byte = 0x81.toByte()

    // --- Protocol V1 Binary Decoders ---

    fun parseRpmTelemetry(bytes: ByteArray): RpmTelemetryState? {
        if (bytes.size != 20) return null
        val version = bytes[0].toInt() and 0xFF
        if (version != 1) return null

        val flags = RpmStatusFlags.fromByte(bytes[1])
        val sequence = (bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8)
        val uptimeMs = (bytes[4].toLong() and 0xFF) or
                ((bytes[5].toLong() and 0xFF) shl 8) or
                ((bytes[6].toLong() and 0xFF) shl 16) or
                ((bytes[7].toLong() and 0xFF) shl 24)

        val provisionalRpm = (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        val rawCandidateCanValue = bytes[10].toInt() and 0xFF
        val captureStage = bytes[11].toInt() and 0xFF

        val totalCanFrames = (bytes[12].toLong() and 0xFF) or
                ((bytes[13].toLong() and 0xFF) shl 8) or
                ((bytes[14].toLong() and 0xFF) shl 16) or
                ((bytes[15].toLong() and 0xFF) shl 24)

        val rpmSampleAgeMs = (bytes[16].toInt() and 0xFF) or ((bytes[17].toInt() and 0xFF) shl 8)
        val reserved = (bytes[18].toInt() and 0xFF) or ((bytes[19].toInt() and 0xFF) shl 8)

        return RpmTelemetryState(
            protocolVersion = version,
            statusFlags = flags,
            sequence = sequence,
            uptimeMs = uptimeMs,
            provisionalRpm = provisionalRpm,
            rawCandidateCanValue = rawCandidateCanValue,
            captureStage = captureStage,
            totalCanFramesReceived = totalCanFrames,
            rpmSampleAgeMs = rpmSampleAgeMs,
            reserved = reserved
        )
    }

    fun parseCanHealth(bytes: ByteArray): CanHealthState? {
        if (bytes.size != 20) return null
        val version = bytes[0].toInt() and 0xFF
        if (version != 1) return null

        val flags = HealthFlags.fromByte(bytes[1])
        val sequence = (bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8)

        val driverMissedFrames = (bytes[4].toLong() and 0xFF) or
                ((bytes[5].toLong() and 0xFF) shl 8) or
                ((bytes[6].toLong() and 0xFF) shl 16) or
                ((bytes[7].toLong() and 0xFF) shl 24)

        val fifoOverruns = (bytes[8].toLong() and 0xFF) or
                ((bytes[9].toLong() and 0xFF) shl 8) or
                ((bytes[10].toLong() and 0xFF) shl 16) or
                ((bytes[11].toLong() and 0xFF) shl 24)

        val canBusErrors = (bytes[12].toLong() and 0xFF) or
                ((bytes[13].toLong() and 0xFF) shl 8) or
                ((bytes[14].toLong() and 0xFF) shl 16) or
                ((bytes[15].toLong() and 0xFF) shl 24)

        val recordedFrames = (bytes[16].toLong() and 0xFF) or
                ((bytes[17].toLong() and 0xFF) shl 8) or
                ((bytes[18].toLong() and 0xFF) shl 16) or
                ((bytes[19].toLong() and 0xFF) shl 24)

        return CanHealthState(
            version = version,
            healthFlags = flags,
            sequence = sequence,
            driverMissedFrames = driverMissedFrames,
            hardwareFifoOverruns = fifoOverruns,
            canBusErrors = canBusErrors,
            recordedFramesInCurrentSession = recordedFrames
        )
    }

    // --- Control Encoders (V1 Protocol) ---

    fun buildFetchHistoricalLogPage(startOffset: Long): ByteArray {
        val frame = ByteArray(6)
        frame[0] = PROTOCOL_VERSION
        frame[1] = CONTROL_OPCODE_FETCH_HISTORICAL_LOG
        val offset = startOffset.coerceAtLeast(0)
        frame[2] = (offset and 0xFF).toByte()
        frame[3] = ((offset shr 8) and 0xFF).toByte()
        frame[4] = ((offset shr 16) and 0xFF).toByte()
        frame[5] = ((offset shr 24) and 0xFF).toByte()
        return frame
    }

    fun buildCancelHistoricalLogRetrieval(): ByteArray {
        return byteArrayOf(PROTOCOL_VERSION, CONTROL_OPCODE_CANCEL_HISTORICAL_LOG)
    }

    fun buildFetchCapturePage(startRecord: Long): ByteArray {
        val frame = ByteArray(6)
        frame[0] = PROTOCOL_VERSION
        frame[1] = CONTROL_OPCODE_FETCH_CAPTURE_PAGE
        val record = startRecord.coerceAtLeast(0)
        frame[2] = (record and 0xFF).toByte()
        frame[3] = ((record shr 8) and 0xFF).toByte()
        frame[4] = ((record shr 16) and 0xFF).toByte()
        frame[5] = ((record shr 24) and 0xFF).toByte()
        return frame
    }

    fun buildCancelCaptureRetrieval(): ByteArray {
        return byteArrayOf(PROTOCOL_VERSION, CONTROL_OPCODE_CANCEL_CAPTURE)
    }

    // --- Legacy / Lighting Compatibility Encoders & Decoders ---

    fun buildFrame(opcode: Byte, sequence: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= 16) { "Payload cannot exceed 16 bytes for V1 frame" }
        val frame = ByteArray(4 + payload.size)
        frame[0] = PROTOCOL_VERSION
        frame[1] = opcode
        frame[2] = sequence
        frame[3] = payload.size.toByte()
        System.arraycopy(payload, 0, frame, 4, payload.size)
        return frame
    }

    fun buildSetPower(power: Boolean, sequence: Byte): ByteArray {
        return buildFrame(CMD_SET_POWER, sequence, byteArrayOf(if (power) 1 else 0))
    }

    fun buildSetBrightness(brightness: Int, sequence: Byte): ByteArray {
        return buildFrame(CMD_SET_BRIGHTNESS, sequence, byteArrayOf(brightness.coerceIn(0, 255).toByte()))
    }

    fun buildSetEffect(effect: EffectType, sequence: Byte): ByteArray {
        return buildFrame(CMD_SET_EFFECT, sequence, byteArrayOf(effect.id.toByte()))
    }

    fun buildSetGlobalColor(color: RgbColor, sequence: Byte): ByteArray {
        return buildFrame(
            CMD_SET_GLOBAL_COLOR,
            sequence,
            byteArrayOf(color.red.toByte(), color.green.toByte(), color.blue.toByte())
        )
    }

    fun buildSetLedColor(index: Int, color: RgbColor, sequence: Byte): ByteArray {
        require(index in 0..5) { "LED index must be 0-5" }
        return buildFrame(
            CMD_SET_LED_COLOR,
            sequence,
            byteArrayOf(index.toByte(), color.red.toByte(), color.green.toByte(), color.blue.toByte())
        )
    }

    fun buildSetEffectSpeed(speed: Int, sequence: Byte): ByteArray {
        return buildFrame(CMD_SET_EFFECT_SPEED, sequence, byteArrayOf(speed.coerceIn(0, 100).toByte()))
    }

    fun buildSetRpmThresholds(thresholds: List<Int>, sequence: Byte): ByteArray {
        require(thresholds.size == 6) { "Must provide exactly 6 thresholds" }
        val payload = ByteArray(12)
        for (i in 0 until 6) {
            val rpm = thresholds[i].coerceIn(0, 65535)
            payload[i * 2] = (rpm and 0xFF).toByte()
            payload[i * 2 + 1] = ((rpm shr 8) and 0xFF).toByte()
        }
        return buildFrame(CMD_SET_RPM_THRESHOLDS, sequence, payload)
    }

    fun buildSetShiftWarning(warningRpm: Int, style: com.pulseshift.app.domain.model.WarningStyle, sequence: Byte): ByteArray {
        val rpm = warningRpm.coerceIn(0, 65535)
        val payload = byteArrayOf(
            (rpm and 0xFF).toByte(),
            ((rpm shr 8) and 0xFF).toByte(),
            style.id.toByte()
        )
        return buildFrame(CMD_SET_SHIFT_WARNING, sequence, payload)
    }

    fun buildSetShiftLedColor(index: Int, color: RgbColor, sequence: Byte): ByteArray {
        require(index in 0..5) { "Shift LED index must be 0-5" }
        return buildFrame(
            CMD_SET_SHIFT_LED_COLOR,
            sequence,
            byteArrayOf(index.toByte(), color.red.toByte(), color.green.toByte(), color.blue.toByte())
        )
    }

    fun buildSetStartupConfig(animationId: Int, durationSec: Int, enabled: Boolean, sequence: Byte): ByteArray {
        return buildFrame(
            CMD_SET_STARTUP_CONFIG,
            sequence,
            byteArrayOf(animationId.toByte(), durationSec.coerceIn(1, 10).toByte(), if (enabled) 1 else 0)
        )
    }

    fun buildSaveToNvs(sequence: Byte): ByteArray {
        return buildFrame(CMD_SAVE_TO_NVS, sequence)
    }

    fun buildResetDefaults(sequence: Byte): ByteArray {
        return buildFrame(
            CMD_RESET_DEFAULTS,
            sequence,
            byteArrayOf(0xA5.toByte())
        )
    }

    fun buildRequestState(sequence: Byte): ByteArray {
        return buildFrame(CMD_REQUEST_STATE, sequence)
    }

    data class AckResponse(
        val sequenceRef: Byte,
        val statusCode: Byte,
        val isSuccess: Boolean
    )

    fun parseAck(bytes: ByteArray): AckResponse? {
        if (bytes.size < 4) return null
        if (bytes[0] != PROTOCOL_VERSION) return null
        if (bytes[1] != FRAME_TYPE_ACK) return null
        val seq = bytes[2]
        val status = bytes[3]
        return AckResponse(
            sequenceRef = seq,
            statusCode = status,
            isSuccess = status == STATUS_SUCCESS
        )
    }

    fun parseState(bytes: ByteArray): LightingState? {
        if (bytes.size < 13) return null
        if (bytes[0] != PROTOCOL_VERSION || bytes[1] != FRAME_TYPE_STATE) return null

        if (bytes.size >= 14) {
            var calcSum: Byte = 0
            for (i in 0..12) {
                calcSum = (calcSum.toInt() xor bytes[i].toInt()).toByte()
            }
            if (calcSum != bytes[13]) return null
        }

        val power = bytes[3].toInt() != 0
        val brightness = bytes[4].toInt() and 0xFF
        val effectId = bytes[5].toInt() and 0xFF
        val speed = bytes[6].toInt() and 0xFF
        val r = bytes[7].toInt() and 0xFF
        val g = bytes[8].toInt() and 0xFF
        val b = bytes[9].toInt() and 0xFF
        val shiftActive = bytes[10].toInt() != 0

        val globalColor = RgbColor(r, g, b)
        return LightingState(
            isPowerOn = power,
            brightness = brightness,
            effect = EffectType.fromId(effectId),
            effectSpeed = speed,
            globalColor = globalColor,
            ledColors = List(6) { globalColor },
            isShiftLightActive = shiftActive,
            isHardwareConfirmed = true,
            isPendingSync = false
        )
    }

    fun parseDeviceInfo(bytes: ByteArray): Pair<String, DeviceCapabilities>? {
        if (bytes.size < 6) return null
        val hwMajor = bytes[1].toInt() and 0xFF
        val hwMinor = bytes[0].toInt() and 0xFF
        val fwMajor = bytes[3].toInt() and 0xFF
        val fwMinor = bytes[2].toInt() and 0xFF
        val capMask = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8)

        val fwVersion = "v$fwMajor.$fwMinor (HW $hwMajor.$hwMinor)"
        val capabilities = DeviceCapabilities.fromBitmask(capMask)
        return Pair(fwVersion, capabilities)
    }

    // --- Configuration V2 Protocol Encoders & Decoders ---

    fun buildV2Frame(opcode: Byte, sequence: Int, payload: ByteArray = ByteArray(0), isStaged: Boolean = false): ByteArray {
        require(payload.size <= 14) { "Payload cannot exceed 14 bytes for V2 frame" }
        val frame = ByteArray(6 + payload.size)
        frame[0] = 0x02 // Protocol Version 2
        frame[1] = opcode
        frame[2] = (sequence and 0xFF).toByte()
        frame[3] = ((sequence shr 8) and 0xFF).toByte()
        frame[4] = payload.size.toByte()
        frame[5] = if (isStaged) 0x01 else 0x00
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, frame, 6, payload.size)
        }
        return frame
    }

    fun buildGetCapabilitiesV2(seq: Int): ByteArray = buildV2Frame(V2_OPCODE_GET_CAPABILITIES, seq)
    fun buildGetSnapshotV2(seq: Int): ByteArray = buildV2Frame(V2_OPCODE_GET_SNAPSHOT, seq)

    fun buildEnrollOwnerV2(seq: Int, ownerSecret: ByteArray): ByteArray {
        require(ownerSecret.size == 12) { "Owner secret must be 12 bytes" }
        return buildV2Frame(V2_OPCODE_ENROLL_OWNER, seq, ownerSecret)
    }

    fun buildRequestChallengeV2(seq: Int): ByteArray {
        return buildV2Frame(V2_OPCODE_REQUEST_CHALLENGE, seq)
    }

    fun buildAuthenticateV2(seq: Int, proof: ByteArray): ByteArray {
        require(proof.size == 8) { "Authentication proof must be 8 bytes" }
        return buildV2Frame(V2_OPCODE_AUTHENTICATE, seq, proof)
    }

    fun computeAuthProof(ownerSecret: ByteArray, nonceBytes: ByteArray, sequence: Int): ByteArray {
        require(ownerSecret.size == 12) { "Owner secret must be 12 bytes" }
        require(nonceBytes.size >= 4) { "Nonce must be at least 4 bytes" }
        val buffer = ByteArray(18)
        System.arraycopy(ownerSecret, 0, buffer, 0, 12)
        System.arraycopy(nonceBytes, 0, buffer, 12, 4)
        buffer[16] = (sequence and 0xFF).toByte()
        buffer[17] = ((sequence shr 8) and 0xFF).toByte()

        val digest = MessageDigest.getInstance("SHA-256").digest(buffer)
        return digest.copyOf(8)
    }

    fun buildSetPowerV2(seq: Int, power: Boolean, isStaged: Boolean = false): ByteArray =
        buildV2Frame(V2_OPCODE_SET_POWER, seq, byteArrayOf(if (power) 1 else 0), isStaged)

    fun buildSetBrightnessV2(seq: Int, brightness: Int, isStaged: Boolean = false): ByteArray =
        buildV2Frame(V2_OPCODE_SET_BRIGHTNESS, seq, byteArrayOf(brightness.coerceIn(0, 255).toByte()), isStaged)

    fun buildSetGlobalRgbV2(seq: Int, color: RgbColor, isStaged: Boolean = false): ByteArray =
        buildV2Frame(V2_OPCODE_SET_GLOBAL_RGB, seq, byteArrayOf(color.red.toByte(), color.green.toByte(), color.blue.toByte()), isStaged)

    fun buildSetLedRgbV2(seq: Int, ledIndex: Int, color: RgbColor, isStaged: Boolean = false): ByteArray =
        buildV2Frame(V2_OPCODE_SET_LED_RGB, seq, byteArrayOf(ledIndex.coerceIn(0, 5).toByte(), color.red.toByte(), color.green.toByte(), color.blue.toByte()), isStaged)

    fun buildSetEffectV2(seq: Int, effectId: Int, speed: Int, isStaged: Boolean = false): ByteArray =
        buildV2Frame(V2_OPCODE_SET_EFFECT, seq, byteArrayOf(effectId.toByte(), speed.coerceIn(0, 100).toByte()), isStaged)

    fun buildSetStartupV2(seq: Int, animationId: Int, durationMs: Int, isStaged: Boolean = false): ByteArray =
        buildV2Frame(V2_OPCODE_SET_STARTUP, seq, byteArrayOf(animationId.toByte(), (durationMs and 0xFF).toByte(), ((durationMs shr 8) and 0xFF).toByte()), isStaged)

    fun buildSetLedThresholdV2(seq: Int, index: Int, rpm: Int, isStaged: Boolean = false): ByteArray =
        buildV2Frame(V2_OPCODE_SET_LED_THRESHOLD, seq, byteArrayOf(index.coerceIn(0, 5).toByte(), (rpm and 0xFF).toByte(), ((rpm shr 8) and 0xFF).toByte()), isStaged)

    fun buildSetShiftWarningV2(seq: Int, rpm: Int, patternId: Int, isStaged: Boolean = false): ByteArray =
        buildV2Frame(V2_OPCODE_SET_SHIFT_WARNING, seq, byteArrayOf((rpm and 0xFF).toByte(), ((rpm shr 8) and 0xFF).toByte(), patternId.toByte()), isStaged)

    fun buildSetRpmMultiplierV2(seq: Int, multiplier: Float, isStaged: Boolean = false): ByteArray {
        val multX1000 = (multiplier * 1000f).toLong().coerceIn(0, 1000000)
        val payload = byteArrayOf(
            (multX1000 and 0xFF).toByte(),
            ((multX1000 shr 8) and 0xFF).toByte(),
            ((multX1000 shr 16) and 0xFF).toByte(),
            ((multX1000 shr 24) and 0xFF).toByte()
        )
        return buildV2Frame(V2_OPCODE_SET_RPM_MULTIPLIER, seq, payload, isStaged)
    }

    fun buildSetCandidateSourceV2(seq: Int, canId: Long, byteIndex: Int, isExtended: Boolean, isStaged: Boolean = false): ByteArray {
        val payload = byteArrayOf(
            (canId and 0xFF).toByte(),
            ((canId shr 8) and 0xFF).toByte(),
            ((canId shr 16) and 0xFF).toByte(),
            ((canId shr 24) and 0xFF).toByte(),
            byteIndex.coerceIn(0, 7).toByte(),
            if (isExtended) 1 else 0
        )
        return buildV2Frame(V2_OPCODE_SET_CANDIDATE_SOURCE, seq, payload, isStaged)
    }

    fun buildSetLedDirectionV2(seq: Int, direction: Int, isStaged: Boolean = false): ByteArray =
        buildV2Frame(V2_OPCODE_SET_LED_DIRECTION, seq, byteArrayOf(direction.toByte()), isStaged)

    fun buildSetWarningRgbV2(seq: Int, color: RgbColor, isStaged: Boolean = false): ByteArray =
        buildV2Frame(V2_OPCODE_SET_WARNING_RGB, seq, byteArrayOf(color.red.toByte(), color.green.toByte(), color.blue.toByte()), isStaged)

    fun buildSaveConfigV2(seq: Int): ByteArray = buildV2Frame(V2_OPCODE_SAVE_CONFIG, seq)
    fun buildRestoreDefaultsV2(seq: Int): ByteArray = buildV2Frame(V2_OPCODE_RESTORE_DEFAULTS, seq, byteArrayOf(0xA5.toByte(), 0x5A.toByte()))
    fun buildReloadConfigV2(seq: Int): ByteArray = buildV2Frame(V2_OPCODE_RELOAD_CONFIG, seq)
    fun buildBeginTransactionV2(seq: Int): ByteArray = buildV2Frame(V2_OPCODE_BEGIN_TRANSACTION, seq)
    fun buildCommitTransactionV2(seq: Int): ByteArray = buildV2Frame(V2_OPCODE_COMMIT_TRANSACTION, seq)
    fun buildAbortTransactionV2(seq: Int): ByteArray = buildV2Frame(V2_OPCODE_ABORT_TRANSACTION, seq)

    // --- Device control & diagnostics encoders (protocol 2.2.0) ---
    fun buildGetDiagnosticsV2(seq: Int): ByteArray = buildV2Frame(V2_OPCODE_GET_DIAGNOSTICS, seq)
    fun buildRestartDeviceV2(seq: Int): ByteArray =
        buildV2Frame(V2_OPCODE_RESTART_DEVICE, seq, byteArrayOf(0xA5.toByte(), 0x5A.toByte()))
    fun buildClearDiagnosticsV2(seq: Int): ByteArray =
        buildV2Frame(V2_OPCODE_CLEAR_DIAGNOSTICS, seq, byteArrayOf(0xA5.toByte(), 0x5A.toByte()))
    fun buildSetCaptureRecordingV2(seq: Int, enable: Boolean): ByteArray =
        buildV2Frame(V2_OPCODE_SET_CAPTURE_RECORDING, seq, byteArrayOf(if (enable) 1 else 0))

    fun buildSetRpmHysteresisV2(seq: Int, hysteresisRpm: Int, isStaged: Boolean = false): ByteArray {
        val h = hysteresisRpm.coerceIn(0, 2000)
        return buildV2Frame(
            V2_OPCODE_SET_RPM_HYSTERESIS, seq,
            byteArrayOf((h and 0xFF).toByte(), ((h shr 8) and 0xFF).toByte()), isStaged
        )
    }

    fun buildSetRpmDecoderExtV2(
        seq: Int,
        byteWidth: Int,
        littleEndian: Boolean,
        offsetRpm: Float,
        maxValidRpm: Int,
        isStaged: Boolean = false
    ): ByteArray {
        val offX1000 = (offsetRpm * 1000f).toInt().coerceIn(-10000000, 10000000)
        val maxValid = maxValidRpm.coerceIn(1, 20000)
        return buildV2Frame(
            V2_OPCODE_SET_RPM_DECODER_EXT, seq,
            byteArrayOf(
                if (byteWidth == 2) 2 else 1,
                if (littleEndian) 1 else 0,
                (offX1000 and 0xFF).toByte(),
                ((offX1000 shr 8) and 0xFF).toByte(),
                ((offX1000 shr 16) and 0xFF).toByte(),
                ((offX1000 shr 24) and 0xFF).toByte(),
                (maxValid and 0xFF).toByte(),
                ((maxValid shr 8) and 0xFF).toByte()
            ),
            isStaged
        )
    }

    // --- Mobile-assisted calibration workflow encoders ---
    fun buildCalBeginV2(seq: Int): ByteArray = buildV2Frame(V2_OPCODE_CAL_BEGIN, seq)
    fun buildCalAbortV2(seq: Int): ByteArray = buildV2Frame(V2_OPCODE_CAL_ABORT, seq)
    fun buildCalCaptureStartV2(seq: Int): ByteArray = buildV2Frame(V2_OPCODE_CAL_CAPTURE_START, seq)
    fun buildCalCaptureStatusV2(seq: Int): ByteArray = buildV2Frame(V2_OPCODE_CAL_CAPTURE_STATUS, seq)
    fun buildCalPointCommitV2(seq: Int, refRpm: Int): ByteArray {
        val rpm = refRpm.coerceIn(0, 20000)
        return buildV2Frame(
            V2_OPCODE_CAL_POINT_COMMIT, seq,
            byteArrayOf((rpm and 0xFF).toByte(), ((rpm shr 8) and 0xFF).toByte())
        )
    }
    fun buildCalComputeV2(seq: Int, apply: Boolean): ByteArray =
        buildV2Frame(V2_OPCODE_CAL_COMPUTE, seq, byteArrayOf(if (apply) 1 else 0))
    fun buildCalStatusV2(seq: Int): ByteArray = buildV2Frame(V2_OPCODE_CAL_STATUS, seq)

    fun parseConfigAck(bytes: ByteArray): com.pulseshift.app.domain.model.ConfigAckResponse? {
        if (bytes.size < 11) return null
        if (bytes[0] != PROTOCOL_V2_VERSION) return null
        val opcode = bytes[1]
        val sequenceRef = (bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8)
        val status = bytes[4]
        val flags = bytes[5]
        val revision = (bytes[6].toLong() and 0xFF) or
                ((bytes[7].toLong() and 0xFF) shl 8) or
                ((bytes[8].toLong() and 0xFF) shl 16) or
                ((bytes[9].toLong() and 0xFF) shl 24)
        val len = bytes[10].toInt() and 0xFF
        val payload = if (bytes.size >= 11 + len && len > 0) bytes.copyOfRange(11, 11 + len) else ByteArray(0)

        return com.pulseshift.app.domain.model.ConfigAckResponse(
            version = 2,
            opcode = opcode,
            sequenceRef = sequenceRef,
            statusCode = status,
            resultFlags = flags,
            activeRevision = revision,
            payload = payload
        )
    }

    fun parseCapabilitiesV2(bytes: ByteArray): DeviceCapabilities? {
        if (bytes.size < 12) return null
        if (bytes[0] != PROTOCOL_V2_VERSION) return null
        val physicalLedCount = bytes[1].toInt() and 0xFF
        val featureMask = (bytes[2].toLong() and 0xFF) or
                ((bytes[3].toLong() and 0xFF) shl 8) or
                ((bytes[4].toLong() and 0xFF) shl 16) or
                ((bytes[5].toLong() and 0xFF) shl 24)
        val configRevision = (bytes[6].toLong() and 0xFF) or
                ((bytes[7].toLong() and 0xFF) shl 8) or
                ((bytes[8].toLong() and 0xFF) shl 16) or
                ((bytes[9].toLong() and 0xFF) shl 24)
        val hardwarePresetCapacity = bytes[10].toInt() and 0xFF
        val securityMode = bytes[11].toInt() and 0xFF

        // Bytes 12-14 carry the firmware version (protocol 2.2.0 and later).
        val fwMajor = if (bytes.size > 12) bytes[12].toInt() and 0xFF else 0
        val fwMinor = if (bytes.size > 13) bytes[13].toInt() and 0xFF else 0
        val fwPatch = if (bytes.size > 14) bytes[14].toInt() and 0xFF else 0

        return DeviceCapabilities.fromV2(
            physicalLedCount = physicalLedCount,
            featureMask = featureMask,
            configRevision = configRevision,
            hardwarePresetCapacity = hardwarePresetCapacity,
            securityMode = securityMode,
            firmwareMajor = fwMajor,
            firmwareMinor = fwMinor,
            firmwarePatch = fwPatch
        )
    }
}
