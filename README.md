# PulseShift Android Application

<p align="center">
  <img src="app/src/main/res/drawable/ic_pulseshift_logo_round.png" width="128" alt="PulseShift Logo" />
</p>

**Native Android companion application for the PulseShift ESP32 motorcycle smart lighting and shift-light controller.**

PulseShift for Android provides complete standalone control over the physical ESP32 controller mounted on the motorcycle. It interfaces exclusively over Bluetooth Low Energy (BLE), offering real-time engine telemetry, lighting customisation, preset storage, diagnostic logs, a full-featured terminal console, and safe dual-slot firmware Over-The-Air (OTA) updates.

- **Firmware repository:** [PulseShift Firmware](https://github.com/iamaritrasaha/PulseShift)
- **Protocol wire specification:** [docs/BLE-PROTOCOL.md](https://github.com/iamaritrasaha/PulseShift/blob/main/docs/BLE-PROTOCOL.md)

### Current Verification Status (2026-11-17)

- **Android and ESP32 bench operation:** The owner reports the Android companion working with the real ESP32 after the USB firmware update. This is a hands-on user report, not an independent certification of every feature.
- **Android build and tests:** Android commit `af3cb34` built successfully; 166 unit tests passed and the debug APK signature was verified in the local recovery session.
- **Firmware compatibility:** Firmware commit `4c7fc5b` was USB-flashed to `app1` and booted with state `VALID`; the existing owner credential and saved device configuration were preserved.
- **Not yet physically validated:** Six-LED visible output, the motorcycle power harness, and end-to-end signed BLE OTA with rollback/failure injection remain pending. A working BLE connection is not proof that OTA is safe for field use.

---

## Table of Contents

1. [Architecture & Design Principles](#1-architecture--design-principles)
2. [Prerequisites & Building from Source](#2-prerequisites--building-from-source)
3. [First Connection & Owner Enrollment](#3-first-connection--owner-enrollment)
4. [Lighting Studio & Configuration Controls](#4-lighting-studio--configuration-controls)
5. [Presets vs Controller NVS Persistence](#5-presets-vs-controller-nvs-persistence)
6. [Interactive Terminal Command Reference](#6-interactive-terminal-command-reference)
7. [Firmware Compatibility & Capability Discovery](#7-firmware-compatibility--capability-discovery)
8. [Safe Dual-Slot Firmware OTA Updates](#8-safe-dual-slot-firmware-ota-updates)
9. [Troubleshooting & Diagnostics](#9-troubleshooting--diagnostics)
10. [Known Limitations & Safety Safeguards](#10-known-limitations--safety-safeguards)

---

### Live Controller Data & Optional Voltage Experiment

The Dashboard's **Live Controller Data** section reads the firmware's existing BLE
RPM telemetry (10 Hz) and health notifications (1 Hz). It displays received CAN
frames, raw RPM candidate, sample age, ESP32 uptime, active traffic, missed CAN
frames, FIFO overruns, bus errors, recording state, preserved-capture flag, and
LittleFS health. Values show an unavailable dash if disconnected, missing, or
stale; this page does not write to the ESP32.

| Desired vehicle signal | Available live? | Evidence / limitation |
|---|---|---|
| Provisional engine RPM | **Yes, uncalibrated** | Current BLE RPM telemetry, CAN 0x301[0] x40. Calibration is not verified. |
| Raw RPM candidate and age | **Yes** | Existing BLE RPM telemetry; freshness is enforced. |
| CAN RX frames, traffic state, driver errors | **Yes** | Existing BLE RPM and health packets. Counters are cumulative for the current controller session. |
| ESP32 uptime and flash/storage flags | **Yes** | Existing BLE RPM and health packets. |
| Motorcycle battery voltage | **Experimental estimate with paired firmware only** | Standard CAN `0x303[1]` raw byte, hypothesized `raw / 10.0 V`. App displays **EXPERIMENTAL** plus the raw byte only when tagged `0xA5` and fresh; old firmware or stale `0x5A` displays `— V`. Not validated against multimeter. |
| Engine/ambient temperature | **No** | 0x302 byte 1 changed during the saved capture; sensor identity and calibration are unknown, and this is not streamed as a live value. Shows `— °C`. |

The November 17, 2026 capture consists exclusively of **23,370 key-on,
engine-off CAN frames**, not reference-calibrated voltage. The paired optional
firmware experiment from the feature branch passively samples `0x303[1]`
without changing the existing RPM decoder or CAN traffic. It publishes a
tagged raw byte using reserved RPM BLE packet positions 18-19. **The Android
APK alone cannot activate the meter**: the matching firmware must be reviewed,
backed up and installed separately using an application-only method that
preserves captured CAN data and owner credentials. No firmware has been
flashed as part of this PR. Measure real battery-terminal voltage independently
at multiple points (key-on, idling, charging) before treating the hypothesis
as verified. Temperature remains unverified and unavailable.

---

## 1. Architecture & Design Principles

The Android application is designed around modern Android development standards and robust embedded integration:

- **Jetpack Compose & Material 3:** Modern UI with dynamic color support (Android 12+ wallpaper palette), custom dark and light themes, and motorcycle dashboard-optimized contrast.
- **Unidirectional Data Flow (UDF):** Clean architecture separating domain models, repository interfaces, DataStore local cache, and BLE transport managers.
- **Strict Wire Fidelity:** The application never fabricates device state. Every slider, switch, or terminal output reflects authoritative controller snapshots and verified sequence-correlated acknowledgments.
- **Defensible Cryptographic Security:** Owner credentials never leave the Android KeyStore. All write mutations and diagnostic captures require challenge-response authentication bound to the BLE connection handle.
- **Decoupled Lifecycle:** The Android application may be redesigned or updated continuously without requiring an ESP32 USB reflash.

---

## 2. Prerequisites & Building from Source

### Requirements
- **Operating System:** Windows 10/11, macOS, or Linux
- **JDK:** Java Development Kit 17 or 21+ (JDK 23 supported via Android Studio JBR: `D:\APPS\Android Studio\jbr`)
- **Android SDK:** Compile SDK 35, Min SDK 26 (Android 8.0 Oreo or higher)
- **Device Requirements:** Physical Android phone with Bluetooth 5.0+ LE support

### Building via Command Line (PowerShell / Bash)

1. Open a terminal in the project directory:
   ```powershell
   cd D:\Development\PulseShift\android
   ```

2. Run unit tests to verify repository, protocol codecs, and parser integrity:
   ```powershell
   .\gradlew.bat testDebugUnitTest
   ```

3. Assemble the debug APK:
   ```powershell
   .\gradlew.bat :app:assembleDebug
   ```

4. Install directly to a connected phone over ADB:
   ```powershell
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```

The compiled package is written to `app\build\outputs\apk\debug\app-debug.apk`.

---

## 3. First Connection & Owner Enrollment

PulseShift implements zero-trust hardware authorization. Live RPM telemetry is broadcast openly for responsiveness, but all configuration writes and diagnostic exports require authenticated ownership.

### Step-by-Step Enrollment Flow

1. **Power Controller:** Power on the motorcycle or bench supply powering the ESP32. The strip plays its startup animation and enters Bluetooth advertising as `PulseShift`.
2. **Open App:** Launch PulseShift and grant Nearby Devices (`BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`) and Location permissions (required on Android 8-11).
3. **Scan & Connect:** Navigate to **Devices**, tap **Scan**, and select your `PulseShift` device.
4. **Physical Authorization Window:**
   - Press the physical **BOOT button (GPIO0)** on the ESP32 DevKit board.
   - The WS2812B LED strip immediately pulses in **Cyan** for 60 seconds.
5. **Enroll Owner:**
   - In the app, tap **Enroll as Owner**.
   - The app generates a cryptographically secure 12-byte random secret, stores it inside the encrypted Android KeyStore, and transmits it via `BLE_CFG_OP_ENROLL_OWNER (0x24)`.
   - The ESP32 saves the hash to persistent NVS storage (`"ps_sec"` namespace).
6. **Subsequent Connections:**
   - You never need to press the BOOT button again during normal riding.
   - Upon connection, the phone automatically performs a challenge-response handshake (`REQUEST_CHALLENGE 0x26` → `AUTHENTICATE 0x25`) with a single-use 32-bit nonce.

---

## 4. Lighting Studio & Configuration Controls

The **Lighting Studio** tab exposes authoritative real-time control over all six WS2812B LEDs:

- **Master Power:** Hardware power switch toggling illumination (`BLE_CFG_OP_SET_POWER 0x10`).
- **Brightness:** Global linear brightness slider from 0 to 255 (`BLE_CFG_OP_SET_BRIGHTNESS 0x11`).
- **Global & Per-LED RGB:**
  - Interactive HSV Color Wheel with quick-select racing presets (Racing Red, Amber Warning, Cyan Glow, White Strobe).
  - Select "All LEDs" for uniform illumination, or tap any individual LED pixel (1 through 6) to configure custom multi-color arrangements (`BLE_CFG_OP_SET_LED_RGB 0x13`).
- **Lighting Effects (`0x14`):**
  - `0: Static Color` - Constant solid illumination.
  - `1: Breathing` - Gentle sine-wave pulse.
  - `2: Rainbow Wave` - Dynamic full-spectrum color flow across the strip.
  - `3: Color Chase` - Sequential high-speed runner beam.
  - `4: RPM Bar (Verified)` - Physical tachometer shift lights (requires verified calibration record).
  - `5: Provisional Preview (Unverified)` - Live preview tracking provisional candidate CAN ID `0x301` (x40 multiplier).
- **Power-On Ignition Animations (`0x15`):**
  - `0: None` - Instantly applies stored illumination on boot.
  - `1: Sweep` - Sequential left-to-right ignition wipe.
  - `2: Wave` - Smooth dual-crested spatial wave greeting.
  - `3: Pulse` - Dynamic dual-heartbeat motorcycle greeting pulse.
  - `4: Center Out` - Drag race staging tree animation expanding from center to outer edges.
  - `5: Strobe Rush` - F1 grid start multi-flash ignition burst sequence.
- **Direction & Warning Alerts:**
  - Forward (LED 1 → 6) or Reverse (LED 6 → 1) progressive sweep (`0x1A`).
  - Shift Warning threshold (default 6,500 RPM) with configurable flash styles: Fast Flash (10 Hz), Strobe Blink (20 Hz), Solid Over-rev, or Alert Off (`0x17`).

---

## 5. Presets vs Controller NVS Persistence

It is vital to understand the difference between local presets and device NVS persistence:

1. **Local Phone Presets (DataStore):**
   - Stored purely inside the phone's local storage.
   - Allows riders to save unlimited custom lighting themes, track profiles, and color schemes.
   - Shipped presets default to 100% brightness (255/255).
2. **Staged Transaction Buffer (RAM):**
   - Applying a preset streams settings into the ESP32's staging buffer (`BLE_CFG_OP_BEGIN_TRANSACTION 0x30`).
   - The device applies changes atomically upon `COMMIT_TRANSACTION (0x31)`.
   - Staged changes are active in RAM immediately, but **will be lost upon power loss** unless committed to non-volatile flash.
3. **Controller NVS Persistence (`CONFIG SAVE`):**
   - Tapping **Save to Controller** or running `CONFIG SAVE` issues `BLE_CFG_OP_SAVE_CONFIG (0x20)`.
   - The active configuration is written to the ESP32's non-volatile NVS flash (`"ps_cfg"` partition).
   - Saved settings survive motorcycle battery disconnects and power cycles.

---

## 6. Interactive Terminal Command Reference

The **Terminal** tab provides an authenticated command console directly mirroring the firmware's serial monitor:

| Command | Arguments | Opcode | Description |
|---|---|---|---|
| `STATUS` / `CAN STATUS` | None | `0x50` | Stream full diagnostics TLV report (uptime, CAN health, error counts). |
| `BLE STATUS` | None | `0x50` | Inspect BLE connection parameters, link auth state, and RSSI. |
| `RPM STATUS` | None | Telemetry | Inspect live candidate CAN ID `0x301` raw byte, estimated RPM, and freshness. |
| `RPM MONITOR ON` / `OFF` | None | Client | Enable/disable real-time 10 Hz streaming transcript in terminal. |
| `RPM MULTIPLIER` | `<0.1..1000.0>` | `0x18` | Adjust provisional candidate multiplier (default 40.0). |
| `CONFIG STATUS` | None | `0x02` | Fetch and display reassembled configuration snapshot TLV. |
| `CONFIG SAVE` | None | `0x20` | Commit current live configuration to ESP32 NVS flash. |
| `CONFIG RESET` | None | `0x21` | Restore safe factory defaults. (Requires confirmation; preserves owner credentials). |
| `EFFECT` | `<0..5>` | `0x14` | Switch lighting effect mode. (Mode 5 = unverified CAN preview). |
| `RPM TEST` | `<0..20000>` | `0x55` | 10-second synthetic bench test illuminating LEDs without real CAN data. |
| `RPM TEST OFF` | None | `0x55` | Terminate synthetic bench test immediately. |
| `STAGE` | `<1\|2\|3>` | `0x54` | Label subsequent flash capture records: 1=Key-On, 2=Idle, 3=RPM sweep. |
| `CAPTURE STOP` / `RESUME`| None | `0x53` | Start or pause CAN raw logging to internal LittleFS flash. |
| `LOGS` | None | `0x10` (v1) | Stream a 20-line page of historical diagnostic logs from flash. |
| `DUMP` / `DUMP NEXT` | None | `0x12` (v1) | Stream a 12-record page of stored raw CAN capture frames. |
| `DUMP CANCEL` | None | `0x13` (v1) | Cancel active capture streaming. |
| `RESTART` | None | `0x51` | Soft-reboot the ESP32 controller. (Requires confirmation). |
| `CLEAR DIAGNOSTICS` | None | `0x52` | Wipe event log `/diag.log`. (Requires confirmation; CAN captures untouched). |
| `AUTH` | None | Help | Display owner commissioning and authentication instructions. |

---

## 7. Firmware Compatibility & Capability Discovery

To guarantee that the Android application can be updated freely without breaking older installed firmware or requiring repeated USB flashes, PulseShift implements strict capability discovery:

- **Protocol Version Negotiation:**
  The app queries the 20-byte `Capabilities (0xd218)` characteristic upon connection.
- **Wire Bytes 15..19:**
  - `maxStartupAnimId` (Byte 15): Advertises maximum supported animation ID (e.g. 5 on v2.3.0, 3 on legacy v2.2.0).
  - `maxEffectId` (Byte 16): Advertises maximum effect index.
  - `otaActiveSlot` (Byte 17): Indicates currently running boot slot (`0` = app0, `1` = app1).
  - `protocolMinor` (Byte 18): Advertises minor protocol revision (`3` for v2.3.0).
  - `otaFlags` (Byte 19): Bit 0 indicates a rollback candidate, bit 1 indicates a dual-slot layout, and bit 2 indicates running app digest support.
- **Dynamic UI Adaptation:**
  The Lighting Studio dynamically filters animation cards by `capabilities.supportedStartupAnimations`. Older firmware will only display animations 0..3, while v2.3.0+ displays all 6 animations.
- **Non-Fatal Snapshot Parsing:**
  If newer firmware introduces unrecognised effect or animation IDs, the Android parser gracefully falls back to safe defaults (`EffectType.STATIC`, `StartupAnimation.SWEEP`) rather than rejecting the snapshot.

---

## 8. Safe Dual-Slot Firmware OTA Updates

PulseShift firmware 2.3.0 introduces authenticated Over-The-Air firmware upgrades directly over BLE, eliminating the need to connect a USB cable to the motorcycle.

> **Hardware validation status:** The OTA code and its cancellation behavior have passed software tests, but a signed OTA transfer, boot confirmation, and rollback sequence have **not yet been demonstrated on the physical ESP32**. Use USB flashing on the bench until those tests pass.

### Safety Invariants & Interlocks

1. **Dual-Partition Architecture:**
   The ESP32 uses dual 1.25 MB application slots (`app0` and `app1`). Updates are always flashed to the currently **inactive** partition. The running application is never overwritten in place.
2. **Stationary Safety Interlock:**
   OTA update is strictly blocked if the motorcycle engine is running (`RPM > 0`) or if active CAN bus traffic has been received within the last 3000 ms. Updates must only be performed with the motorcycle parked on a stable power supply.
3. **Cryptographic ECDSA P-256 & SHA-256 Authentication:**
   Images must be signed using the project private key with `tools/sign_firmware.py`, producing a 76-byte `PSOT` authentication trailer. Android checks the trailer format and payload CRC. The ESP32 incrementally hashes the payload and verifies the ECDSA signature against its embedded public key before selecting the boot partition.
4. **Negotiated MTU & Efficient BLE Transfer:**
   The app sizes each chunk using the negotiated MTU, up to 230 bytes. Each chunk advances progress after the ESP32 acknowledges its index and written byte count.
5. **Bootloader Self-Test & Rollback:**
   With a compatible installed bootloader and a rollback candidate, the new slot boots in `ESP_OTA_IMG_PENDING_VERIFY`. The firmware checks LittleFS, active configuration, TWAI health, and BLE readiness. Confirmation must occur within its 120-second window. Verify the installed bootloader and rollback on the bench before field use.
6. **Post-Reboot Verification & No False Success:**
   The Android app reconnects after reboot and performs strict validation:
   - Checks that the running slot matches the target slot and that the firmware reports a version.
   - Compares the running ESP32 app digest with the selected image when the firmware supports the digest query. For ESP32 images without an appended validation hash, it compares the SHA-256 of the entire app payload. For older firmware it reports confirmation of the boot slot and leaves image identity unverified.
   - Reauthenticates the owner on the **new** BLE connection. Authentication does not persist across a reboot.
   - Queries `BLE_CFG_OP_OTA_STATUS (0x65)` for actual bootloader image state and the presence of a rollback candidate. A missing or unsafe state is a hard failure.
   - Issues `BLE_CFG_OP_OTA_CONFIRM (0x64)` only after reauthentication and state verification. If an ACK is lost, it retries once using the idempotent confirmation command.
   - The app reports the confirmation error status rather than claiming success when the handshake is missing or rejected.

   **Cancellation:** Cancel stops sending chunks and requests OTA_ABORT while the transfer is active. The dialog reports cancellation as confirmed only after an ESP32 acknowledgment. Once the app starts OTA_END, it proceeds with verification.

   **Rollback prerequisite:** The Arduino-ESP32 3.3.12 sketch overrides the core's `verifyRollbackLater()` hook to defer validation until owner confirmation. The installed bootloader controls the transition to pending verification and must support rollback.

### OTA Update Procedure

1. Compile the production firmware:
   ```bash
   arduino-cli compile --fqbn esp32:esp32:esp32doit-devkit-v1 PulseShift
   ```
2. Sign the binary using the private signing key:
   ```bash
   uv run --with cryptography python tools/sign_firmware.py sign \
     --input PulseShift.ino.bin \
     --key tools/keys/pulseshift_ota_key.pem \
     --output PulseShift_signed.bin
   ```
3. Copy `PulseShift_signed.bin` to your phone.
4. Connect to PulseShift via BLE and authenticate your owner session.
5. Navigate to **Settings → Firmware & Updates**.
6. Tap **Flash .bin** and select `PulseShift_signed.bin`.
7. The app verifies the binary header magic (`0xE9`), validates size limits (64 KB - 1.25 MB), checks the `PSOT` trailer, and streams the signed binary.
8. Once streaming finishes, the ESP32 verifies checksums and ECDSA signature, swaps the active boot partition, and reboots.
9. The app awaits BLE reconnection and confirms that the new firmware version is active on the target slot before reporting success.

> [!NOTE]
> For hardware bench-testing procedures, failure-injection tests, and universal 4-artifact USB recovery instructions, see [docs/OTA-BENCH-TEST-PLAN.md](https://github.com/iamaritrasaha/PulseShift/blob/main/docs/OTA-BENCH-TEST-PLAN.md) in the firmware repository.

---

## 9. Troubleshooting & Diagnostics

| Problem | Cause | Solution |
|---|---|---|
| App cannot find `PulseShift` | BLE disabled or permissions missing | Enable Bluetooth; ensure Nearby Devices and Location permissions are granted. |
| Commands rejected with `UNAUTHORIZED (4)` | Session not authenticated | Open **Devices**, press the physical ESP32 BOOT button (cyan pulse), and tap **Enroll as Owner**. |
| Slider changes revert after reconnect | Settings were not persisted | Run `CONFIG SAVE` in Terminal or tap **Save to Controller** in Lighting Studio. |
| RPM reads `---` or `STALE` | No CAN frames or vehicle key off | Switch motorcycle ignition ON. Verify SN65HVD230 wiring (GPIO16 CRX, 3V3 CTX). |
| Shift warning not triggering | Warning disabled or below threshold | Ensure Shift Warning pattern is set to Flash, Strobe, or Solid, and warning RPM is configured properly. |
| OTA Flash button disabled | Link disconnected or engine active | Connect via BLE, authenticate owner session, and ensure motorcycle engine is off (0 RPM). |

---

## 10. Known Limitations & Safety Safeguards

- **Provisional Engine Speed (CAN ID `0x301`):** The preserved 23,370-record motorcycle capture passed CRC16 validation after USB flashing, but the RPM conversion multiplier (40.0) remains an unverified provisional estimate. Shift light mode 4 is disabled until independent tachometer calibration is executed.
- **Strictly Passive CAN:** The firmware never transmits CAN frames. CTX is clamped to 3V3, GPIO17 is air-gapped, and the TWAI controller operates exclusively in listen-only mode.
- **Physical BOOT Requirement for Key Enrollment:** Physical button access is required to enroll a phone or recover credentials. This guarantees that nearby unauthorised BLE devices cannot hijack shift light or recording parameters.
