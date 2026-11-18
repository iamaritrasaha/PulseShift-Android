package com.pulseshift.app.data.ble.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pulseshift.app.MainActivity
import com.pulseshift.app.PulseShiftApplication
import com.pulseshift.app.R
import com.pulseshift.app.data.ble.BleConnectionState
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.OtaUpdateState
import com.pulseshift.app.domain.model.RpmTelemetryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground Service for continuous Bluetooth Low Energy communication with the
 * PulseShift motorcycle lighting controller.
 *
 * Uses the Android 14+ [ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE] service type.
 * Maintains connection and telemetry streaming while the phone is locked or another app
 * (such as navigation) is in the foreground.
 */
class PulseShiftBleService : Service() {

    companion object {
        const val ACTION_START = "com.pulseshift.app.action.START_BLE_SERVICE"
        const val ACTION_STOP = "com.pulseshift.app.action.STOP_BLE_SERVICE"
        const val ACTION_DISCONNECT = "com.pulseshift.app.action.DISCONNECT_DEVICE"

        const val CHANNEL_ID = "pulseshift_connected_device_channel"
        const val NOTIFICATION_ID = 1001

        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 1000L

        fun start(context: Context) {
            val intent = Intent(context, PulseShiftBleService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // If background launch restrictions prevent start, service will start on next foreground interaction
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PulseShiftBleService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {
                try {
                    context.stopService(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateObservationJob: Job? = null
    private var lastNotificationUpdateMs = 0L
    private var lastRenderedRpm: Int? = null
    private var lastRenderedState: BleConnectionState? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                val app = application as? PulseShiftApplication
                app?.bleManager?.disconnect()
                stopForegroundService()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopForegroundService()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                startForegroundWithNotification()
                observeControllerState()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stateObservationJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification(
            state = BleConnectionState.CONNECTING,
            device = null,
            rpm = null,
            ota = OtaUpdateState.Idle
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundService() {
        stateObservationJob?.cancel()
        stateObservationJob = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun observeControllerState() {
        if (stateObservationJob?.isActive == true) return
        val app = application as? PulseShiftApplication ?: return
        val repo = app.repository

        stateObservationJob = serviceScope.launch {
            combine(
                repo.connectionState,
                repo.connectedDevice,
                repo.rpmTelemetryState,
                repo.otaUpdateState
            ) { state, device, rpm, ota ->
                StateBundle(state, device, rpm, ota)
            }.collect { bundle ->
                updateNotificationIfNeeded(bundle)
            }
        }
    }

    private data class StateBundle(
        val state: BleConnectionState,
        val device: BleDevice?,
        val rpm: RpmTelemetryState?,
        val ota: OtaUpdateState
    )

    private fun updateNotificationIfNeeded(bundle: StateBundle) {
        val now = System.currentTimeMillis()
        val isOta = bundle.ota !is OtaUpdateState.Idle
        val currentRpm = bundle.rpm?.takeIf { it.isRpmAvailable && !it.isStale }?.provisionalRpm
        val stateChanged = bundle.state != lastRenderedState
        val rpmChangedSignificantly = currentRpm != lastRenderedRpm &&
            (currentRpm == null || lastRenderedRpm == null || Math.abs(currentRpm - (lastRenderedRpm ?: 0)) >= 100)

        val timeElapsed = now - lastNotificationUpdateMs >= NOTIFICATION_UPDATE_INTERVAL_MS

        if (stateChanged || isOta || (timeElapsed && rpmChangedSignificantly)) {
            lastNotificationUpdateMs = now
            lastRenderedRpm = currentRpm
            lastRenderedState = bundle.state

            val notification = buildNotification(bundle.state, bundle.device, bundle.rpm, bundle.ota)
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        state: BleConnectionState,
        device: BleDevice?,
        rpm: RpmTelemetryState?,
        ota: OtaUpdateState
    ): Notification {
        val title: String
        val content: String

        val isOtaActive = ota !is OtaUpdateState.Idle

        if (isOtaActive) {
            title = "PulseShift · Updating Firmware"
            content = when (ota) {
                is OtaUpdateState.Transferring -> {
                    val pct = if (ota.totalBytes > 0) (ota.bytesSent * 100 / ota.totalBytes) else 0
                    "Streaming firmware package: $pct% (slot app${ota.currentSlot})"
                }
                is OtaUpdateState.Validating -> "Validating firmware package cryptographic signature..."
                is OtaUpdateState.Verifying -> "Verifying flash integrity and SHA-256..."
                is OtaUpdateState.Rebooting -> "Controller rebooting (active in ${ota.secondsRemaining}s)..."
                is OtaUpdateState.Reconnecting -> "Reconnecting over Bluetooth..."
                is OtaUpdateState.Confirming -> "Verifying running firmware image..."
                is OtaUpdateState.Success -> "Firmware update successful (v${ota.newVersion} on app${ota.activeSlot})."
                is OtaUpdateState.Failed -> "Update failed: ${ota.reason}"
                is OtaUpdateState.Cancelling, OtaUpdateState.Cancelled -> "Firmware update cancelled."
                OtaUpdateState.Idle -> "Firmware update pending"
            }
        } else {
            val devName = device?.name ?: "PulseShift ESP32"
            when (state) {
                BleConnectionState.RECEIVING_TELEMETRY,
                BleConnectionState.READY,
                BleConnectionState.CONNECTED_WAITING_FOR_DATA -> {
                    title = "PulseShift · Connected"
                    val available = rpm != null && rpm.isRpmAvailable && !rpm.isStale
                    content = if (available) {
                        String.format(Locale.US, "%s · %,d RPM", devName, rpm.provisionalRpm)
                    } else {
                        "$devName · Awaiting engine telemetry"
                    }
                }
                BleConnectionState.CONNECTING,
                BleConnectionState.DISCOVERING_SERVICES,
                BleConnectionState.SUBSCRIBING -> {
                    title = "PulseShift · Connecting"
                    content = "Connecting to $devName..."
                }
                BleConnectionState.SCANNING,
                BleConnectionState.DEVICE_DISCOVERED -> {
                    title = "PulseShift · Scanning"
                    content = "Searching for PulseShift controller..."
                }
                BleConnectionState.DISCONNECTED -> {
                    title = "PulseShift · Disconnected"
                    content = "No active controller link"
                }
                BleConnectionState.BLUETOOTH_DISABLED -> {
                    title = "PulseShift · Bluetooth Off"
                    content = "Enable Bluetooth to connect"
                }
                BleConnectionState.PERMISSION_REQUIRED -> {
                    title = "PulseShift · Permission Needed"
                    content = "Bluetooth permissions required"
                }
                BleConnectionState.ERROR -> {
                    title = "PulseShift · Link Error"
                    content = "Connection dropped. Auto-reconnecting..."
                }
                BleConnectionState.UNSUPPORTED -> {
                    title = "PulseShift · Unsupported"
                    content = "Bluetooth LE is not supported on this device"
                }
            }
        }

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, PulseShiftBleService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isConnectedOrConnecting = state == BleConnectionState.READY ||
            state == BleConnectionState.RECEIVING_TELEMETRY ||
            state == BleConnectionState.CONNECTED_WAITING_FOR_DATA ||
            state == BleConnectionState.CONNECTING ||
            state == BleConnectionState.DISCOVERING_SERVICES ||
            state == BleConnectionState.SUBSCRIBING

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_pulseshift_logo_round)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(isConnectedOrConnecting || isOtaActive)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (isConnectedOrConnecting && !isOtaActive) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Disconnect",
                    disconnectPendingIntent
                ).build()
            )
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "PulseShift Device Link"
            val descriptionText = "Displays connection status and live engine speed while connected to the motorcycle."
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }
}
