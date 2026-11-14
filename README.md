# PulseShift Android App

<p align="center">
  <img src="app/src/main/res/drawable/ic_pulseshift_logo_round.png" width="128" alt="PulseShift Logo" />
</p>

Companion app for the PulseShift ESP32 shift-light controller (see the
[firmware repository](https://github.com/iamaritrasaha/PulseShift) and its
`docs/BLE-PROTOCOL.md` for the wire contract).

## Build & install (first time)

1. Install **Android Studio** (or just its SDK) and open this folder
   (`D:\Development\PulseShift\android`) as a project.
2. Let Gradle sync (JDK 23 is preconfigured via `gradle.properties`).
3. Build and install on a phone with Android 8.0+ (BLE required):
   ```powershell
   .\gradlew.bat :app:assembleDebug
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```
   The debug APK is also prebuilt at
   `app\build\outputs\apk\debug\app-debug.apk` after any build.
4. On the phone: allow **Nearby devices** permission (Android 12+ asks for
   `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`) and turn Bluetooth on.

## First connection

1. Flash the ESP32 with the firmware (Arduino IDE: board "ESP32 Dev Module",
   partition "Default 4MB with spiffs", upload from the firmware repo).
2. Power the ESP32. It advertises as **PulseShift**.
3. App → **Devices** tab → *Scan Now* → Connect.
4. **Owner enrollment (once per device):** press the physical **BOOT** button
   on the ESP32 (a 60-second cyan pulse opens the commissioning window), then
   in the app choose *Enroll as Owner* and confirm. The secret is generated on
   the phone, stored in the Android KeyStore and proven by challenge-response
   on every later connection.
5. The Dashboard now shows live estimated RPM (labeled **UNVERIFIED** until a
   firmware-driven calibration is completed from the RPM tab).

## Terminal

Home → **Terminal** gives a command console over the authenticated BLE
protocol (`HELP` lists everything). Destructive commands (CONFIG RESET,
RESTART, CLEAR DIAGNOSTICS) ask for confirmation; USB-only operations
(STREAM, CLEAR YES) are refused with an explanation instead of faked.
