package com.pulseshift.app.domain.terminal

/**
 * Parsed terminal command model.
 *
 * The Android terminal is a human-readable client for the versioned BLE
 * protocol (see firmware docs/ANDROID-TERMINAL.md). User input is parsed into
 * one of these commands, validated, and then dispatched to a real BLE
 * operation. Nothing here fabricates a response; the dispatcher matches the
 * actual firmware ACK by opcode + sequence.
 *
 * Commands flagged [requiresConfirmation] must show an explicit confirmation
 * dialog before dispatch.
 */
sealed class TerminalCommand {

    /** Confirmations the parser attaches to destructive/disruptive commands. */
    enum class Kind { STATUS, WRITE, DESTRUCTIVE, LOCAL }

    /** What executing this command needs from the BLE session. */
    enum class Transport { BLE, LOCAL_ONLY, UNSUPPORTED_OVER_BLE }

    // --- Informational / status queries ---

    data object Help : TerminalCommand()
    data object Status : TerminalCommand() // STATUS / CAN STATUS -> GET_DIAGNOSTICS 0x50
    data object BleStatus : TerminalCommand() // BLE STATUS -> GET_DIAGNOSTICS 0x50
    data object RpmStatus : TerminalCommand() // RPM STATUS -> live telemetry + TLV candidate
    data object RpmMonitorOn : TerminalCommand()
    data object RpmMonitorOff : TerminalCommand()
    data object RpmMonitorSample : TerminalCommand() // RPM MONITOR (instantaneous)
    data object ConfigStatus : TerminalCommand() // CONFIG STATUS -> GET_SNAPSHOT 0x02

    // --- Configuration writes ---

    /** CONFIG SAVE -> SAVE_CONFIG 0x20; success requires the PERSISTED flag. */
    data object ConfigSave : TerminalCommand()

    /** CONFIG RESET -> RESTORE_DEFAULTS 0x21 with A5 5A; owner credentials preserved. */
    data object ConfigReset : TerminalCommand()

    /**
     * RPM MULTIPLIER <m> -> SET_RPM_MULTIPLIER 0x18, payload u32 LE round(m*1000).
     * Live change only; invalidates any verified calibration. Not persisted
     * until CONFIG SAVE succeeds.
     */
    data class RpmMultiplier(val multiplier: Double) : TerminalCommand()

    /**
     * EFFECT <0..5> -> SET_EFFECT 0x14, payload [effectId, speed].
     * Effect 5 is the UNVERIFIED provisional CAN RPM preview; effect 4 requires
     * a calibration record bound to the current decoder fingerprint.
     */
    data class Effect(val effectId: Int) : TerminalCommand()

    // --- Device control ---

    /**
     * RPM TEST <0..20000> -> SET_LED_TEST_RPM 0x55 payload [rpm u16LE, 1].
     * Nonpersistent 10-second LED bench test driven by a synthetic RPM value;
     * this is NOT CAN data and never marks calibration as verified.
     */
    data class RpmTest(val targetRpm: Int) : TerminalCommand()

    /** RPM TEST OFF -> SET_LED_TEST_RPM 0x55 payload [0, 0]. */
    data object RpmTestOff : TerminalCommand()

    /**
     * STAGE <1|2|3> -> SET_CAPTURE_STAGE 0x54 payload [stage].
     * Labels subsequent capture records only; does not move the motorcycle.
     */
    data class CaptureStage(val stage: Int) : TerminalCommand()

    /** CAPTURE STOP / CAPTURE PAUSE -> SET_CAPTURE_RECORDING 0x53 payload [0]. */
    data object CaptureStop : TerminalCommand()

    /**
     * CAPTURE RESUME / CAPTURE START -> SET_CAPTURE_RECORDING 0x53 payload [1].
     * The device may reject this when flash is preserved, damaged or full.
     */
    data object CaptureResume : TerminalCommand()

    // --- Capture / log export ---

    /** LOGS -> v1 GET_LOG_PAGE 0x10 bounded page stream. */
    data object Logs : TerminalCommand()

    /** DUMP / DUMP NEXT -> v1 GET_CAPTURE_PAGE 0x12 from the phone cursor. */
    data object DumpNext : TerminalCommand()

    /** DUMP PAGE <n> -> 0x12 at the record offset for page n (12 records/page). */
    data class DumpPage(val page: Int) : TerminalCommand()

    /** DUMP ALL -> sequential bounded BLE pages with ACK backpressure; cancelable. */
    data object DumpAll : TerminalCommand()

    /** DUMP CANCEL -> v1 CANCEL_CAPTURE 0x13. */
    data object DumpCancel : TerminalCommand()

    // --- Destructive / disruptive (always require confirmation) ---

    /** RESTART -> RESTART_DEVICE 0x51 with A5 5A. */
    data object Restart : TerminalCommand()

    /**
     * CLEAR DIAGNOSTICS -> CLEAR_DIAGNOSTICS 0x52 with A5 5A.
     * Clears the diagnostic event log ONLY; CAN captures are untouched.
     */
    data object ClearDiagnostics : TerminalCommand()

    // --- Local-only commands ---

    /** AUTH -> shows physical BOOT-button enrollment instructions; no secret bypass. */
    data object Auth : TerminalCommand()

    /** STREAM ON/OFF is USB-serial only; print UNSUPPORTED OVER BLE, never fake it. */
    data object StreamUnsupported : TerminalCommand()

    /** CLEAR YES is an irreversible USB-only capture wipe; not exposed over BLE. */
    data object ClearWipeUnsupported : TerminalCommand()

    // --- Metadata for help & guard rails ---

    val kind: Kind
        get() = when (this) {
            Help, Auth, RpmMonitorSample, StreamUnsupported,
            ClearWipeUnsupported -> Kind.LOCAL
            ConfigReset, Restart, ClearDiagnostics -> Kind.DESTRUCTIVE
            is RpmMultiplier, is Effect, is RpmTest, is RpmTestOff, is CaptureStage,
            CaptureStop, CaptureResume, ConfigSave, RpmMonitorOn, RpmMonitorOff -> Kind.WRITE
            Status, BleStatus, RpmStatus, ConfigStatus, Logs, DumpNext, is DumpPage,
            is DumpAll, DumpCancel -> Kind.STATUS
        }

    val transport: Transport
        get() = when (this) {
            Help, Auth, RpmMonitorSample, StreamUnsupported, ClearWipeUnsupported -> Transport.LOCAL_ONLY
            Status, BleStatus, RpmStatus, ConfigStatus, ConfigSave, ConfigReset,
            is RpmMultiplier, is Effect, is RpmTest, RpmTestOff, is CaptureStage,
            CaptureStop, CaptureResume, Logs, DumpNext, is DumpPage, DumpAll,
            DumpCancel, Restart, ClearDiagnostics, RpmMonitorOn, RpmMonitorOff -> Transport.BLE
        }

    val requiresConfirmation: Boolean
        get() = kind == Kind.DESTRUCTIVE
}
