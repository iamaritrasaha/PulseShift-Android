package com.pulseshift.app.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.pulseshift.app.BuildConfig
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.CanHealthState
import com.pulseshift.app.domain.model.DeviceCapabilities
import com.pulseshift.app.domain.model.DiagnosticLogChunk
import com.pulseshift.app.domain.model.DiagnosticLogMessage
import com.pulseshift.app.domain.model.HistoricalPageEnd
import com.pulseshift.app.domain.model.LogSeverity
import com.pulseshift.app.domain.model.RpmTelemetryState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.LinkedList
import java.util.Queue
import java.util.UUID

enum class BleConnectionState {
    UNSUPPORTED,
    BLUETOOTH_DISABLED,
    PERMISSION_REQUIRED,
    DISCONNECTED,
    SCANNING,
    DEVICE_DISCOVERED,
    CONNECTING,
    DISCOVERING_SERVICES,
    SUBSCRIBING,
    CONNECTED_WAITING_FOR_DATA,
    RECEIVING_TELEMETRY,
    READY,
    ERROR
}

sealed class GattOp {
    data class ReadChar(val characteristic: BluetoothGattCharacteristic) : GattOp()
    data class WriteChar(
        val characteristic: BluetoothGattCharacteristic,
        val value: ByteArray,
        val writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    ) : GattOp() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as WriteChar
            if (characteristic != other.characteristic) return false
            if (!value.contentEquals(other.value)) return false
            if (writeType != other.writeType) return false
            return true
        }

        override fun hashCode(): Int {
            var result = characteristic.hashCode()
            result = 31 * result + value.contentHashCode()
            result = 31 * result + writeType
            return result
        }
    }
    data class WriteDesc(val descriptor: BluetoothGattDescriptor, val value: ByteArray) : GattOp() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as WriteDesc
            if (descriptor != other.descriptor) return false
            if (!value.contentEquals(other.value)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = descriptor.hashCode()
            result = 31 * result + value.contentHashCode()
            return result
        }
    }
    data class EnableNotification(
        val characteristic: BluetoothGattCharacteristic,
        val enable: Boolean = true
    ) : GattOp()
    object ReadRssi : GattOp()
}

class PulseShiftBleManager(
    val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job()),
    private val adapterProvider: () -> BluetoothAdapter? = {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }
) {
    companion object {
        private const val TAG = "PulseShiftBle"

        /** Fixed high sequence for manager-initiated snapshot fetches; the
         *  repository allocates 1..N sequentially, so no collision occurs. */
        private const val SNAPSHOT_REQUEST_SEQ = 0xFF00

        fun gattStatusToString(status: Int): String {
            return when (status) {
                BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS (0x00)"
                8 -> "GATT_CONN_TIMEOUT (0x08)"
                19 -> "GATT_CONN_TERMINATE_PEER_USER (0x13)"
                22 -> "GATT_CONN_TERMINATE_LOCAL_HOST (0x16)"
                62 -> "GATT_CONN_FAIL_ESTABLISH (0x3E)"
                133 -> "GATT_ERROR (0x85)"
                256 -> "GATT_INTERNAL_ERROR (0x100)"
                else -> "GATT_STATUS_$status (0x${Integer.toHexString(status)})"
            }
        }

        fun explainGattStatus(status: Int): String {
            return when (status) {
                BluetoothGatt.GATT_SUCCESS -> "Disconnected normally (GATT status 0: GATT_SUCCESS)."
                8 -> "Connection timed out (GATT status 8: GATT_CONN_TIMEOUT)."
                19 -> "Device terminated connection (GATT status 19: GATT_CONN_TERMINATE_PEER_USER)."
                22 -> "Local host terminated connection (GATT status 22: GATT_CONN_TERMINATE_LOCAL_HOST)."
                62 -> "Connection failed to establish (GATT status 62: GATT_CONN_FAIL_ESTABLISH)."
                133 -> "Android BLE stack error (GATT status 133: GATT_ERROR)."
                256 -> "GATT internal error (GATT status 256: GATT_INTERNAL_ERROR)."
                else -> "GATT error status $status (0x${Integer.toHexString(status)})."
            }
        }
    }

    private val bluetoothAdapter: BluetoothAdapter? get() = adapterProvider()

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    /** Last fatal transport error surfaced to the UI (null when healthy). */
    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDevice>> = _discoveredDevices.asStateFlow()

    private val _connectedDevice = MutableStateFlow<BleDevice?>(null)
    val connectedDevice: StateFlow<BleDevice?> = _connectedDevice.asStateFlow()

    // Telemetry & Health States (V1 Protocol)
    private val _rpmTelemetryState = MutableStateFlow<RpmTelemetryState?>(null)
    val rpmTelemetryState: StateFlow<RpmTelemetryState?> = _rpmTelemetryState.asStateFlow()

    private val _canHealthState = MutableStateFlow<CanHealthState?>(null)
    val canHealthState: StateFlow<CanHealthState?> = _canHealthState.asStateFlow()

    // Log Chunk Reassembler & Flows
    val logReassembler = DiagnosticLogReassembler()
    val diagnosticLogMessages: SharedFlow<DiagnosticLogMessage> = logReassembler.completedMessages
    val pageEndEvents: SharedFlow<HistoricalPageEnd> = logReassembler.pageEndEvents

    private val _rawLogChunks = MutableSharedFlow<DiagnosticLogChunk>(extraBufferCapacity = 64)
    val rawLogChunks: SharedFlow<DiagnosticLogChunk> = _rawLogChunks.asSharedFlow()

    // Configuration V2 Snapshot Reassembler & Flows
    val snapshotReassembler = ConfigSnapshotReassembler()

    private val _configAcksV2 = MutableSharedFlow<com.pulseshift.app.domain.model.ConfigAckResponse>(extraBufferCapacity = 16)
    val configAcksV2: SharedFlow<com.pulseshift.app.domain.model.ConfigAckResponse> = _configAcksV2.asSharedFlow()

    private val _configSnapshots = MutableStateFlow<com.pulseshift.app.domain.model.ConfigSnapshot?>(null)
    val configSnapshots: StateFlow<com.pulseshift.app.domain.model.ConfigSnapshot?> = _configSnapshots.asStateFlow()

    private var activeGatt: BluetoothGatt? = null

    // Characteristics (V1 Protocol)
    private var rpmCharacteristic: BluetoothGattCharacteristic? = null
    private var healthCharacteristic: BluetoothGattCharacteristic? = null
    private var logCharacteristic: BluetoothGattCharacteristic? = null
    private var controlCharacteristic: BluetoothGattCharacteristic? = null

    // Characteristics (V2 Configuration Protocol)
    private var configCmdCharacteristic: BluetoothGattCharacteristic? = null
    private var configResultCharacteristic: BluetoothGattCharacteristic? = null
    private var configSnapshotCharacteristic: BluetoothGattCharacteristic? = null
    private var capabilitiesCharacteristic: BluetoothGattCharacteristic? = null

    // Unified Serialized GATT Operation Queue
    private val gattOpQueue: Queue<GattOp> = LinkedList()
    private var activeGattOp: GattOp? = null
    private var isGattOpPending = false
    private var opTimeoutJob: Job? = null

    // Historical Log State Machine
    private var isHistoricalRetrievalActive = false

    // CAN capture page retrieval state
    var isCaptureRetrievalActive = false

    // Auto-reconnect support (user preference, applied on unexpected drops)
    private var autoReconnectEnabled = false
    private var lastConnectedAddress: String? = null
    private var userInitiatedDisconnect = false
    private var reconnectAttempts = 0
    private var connectedSinceMs = 0L
    private var connectTimeoutJob: Job? = null
    private var serviceReadinessTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var healthySessionJob: Job? = null
    private val _isReconnecting = MutableStateFlow(false)
    val isReconnecting: StateFlow<Boolean> = _isReconnecting.asStateFlow()

    internal fun logDiagnosticEvent(text: String, severity: LogSeverity = LogSeverity.INFO) {
        logReassembler.emitDirectMessage(
            DiagnosticLogMessage(
                id = UUID.randomUUID().toString(),
                messageId = 0xFFFF,
                timestampMs = System.currentTimeMillis(),
                messageText = text,
                severity = severity,
                isHistorical = false,
                isCorruptOrIncomplete = false
            )
        )
    }

    fun setAutoReconnectEnabled(enabled: Boolean) {
        autoReconnectEnabled = enabled
        if (!enabled) {
            reconnectJob?.cancel()
            reconnectJob = null
            _isReconnecting.value = false
        }
    }

    private fun scheduleReconnect(errorDetail: String? = null) {
        if (!autoReconnectEnabled || userInitiatedDisconnect) {
            _isReconnecting.value = false
            return
        }
        val address = lastConnectedAddress ?: run {
            _isReconnecting.value = false
            return
        }
        // A connection that stayed up for a while was a genuinely healthy
        // session: the counter is per-episode, not per-app-lifetime.
        if (connectedSinceMs > 0 && (System.currentTimeMillis() - connectedSinceMs > 30_000L)) {
            reconnectAttempts = 0
        }
        if (reconnectAttempts >= 5) {
            _isReconnecting.value = false
            val prefix = if (errorDetail != null) "$errorDetail " else ""
            _connectionError.value = "${prefix}Reconnection gave up after 5 attempts; tap Connect to retry."
            return
        }
        val attempt = ++reconnectAttempts
        _isReconnecting.value = true
        val backoffMs = minOf(1000L * attempt, 5000L)
        val prefix = if (errorDetail != null) "$errorDetail " else ""
        _connectionError.value = "${prefix}Reconnecting (attempt $attempt/5 in ${backoffMs / 1000}s)..."

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // Linear backoff: 1s, 2s, 3s, 4s, 5s capped at 5s.
            delay(backoffMs)
            if (!autoReconnectEnabled || userInitiatedDisconnect) {
                _isReconnecting.value = false
                return@launch
            }
            val adapter = bluetoothAdapter ?: run {
                _isReconnecting.value = false
                _connectionState.value = BleConnectionState.UNSUPPORTED
                return@launch
            }
            if (!adapter.isEnabled) {
                _isReconnecting.value = false
                _connectionState.value = BleConnectionState.BLUETOOTH_DISABLED
                return@launch
            }
            val device = try {
                adapter.getRemoteDevice(address)
            } catch (e: Exception) {
                null
            } ?: run {
                _isReconnecting.value = false
                _connectionError.value = "Invalid device address: $address"
                _connectionState.value = BleConnectionState.ERROR
                return@launch
            }
            _connectionState.value = BleConnectionState.CONNECTING
            activeGatt?.close()
            activeGatt = null
            val gatt = try {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                Log.w(TAG, "connectGatt denied: ${e.message}")
                _isReconnecting.value = false
                _connectionState.value = BleConnectionState.PERMISSION_REQUIRED
                return@launch
            }
            if (gatt == null) {
                _isReconnecting.value = false
                _connectionState.value = BleConnectionState.ERROR
                return@launch
            }
            activeGatt = gatt

            // Start independent connection timeout job per attempt
            startConnectTimeoutJob(address)
        }
    }

    // Reboot tracking (uptime regression detection)
    private var lastUptimeMs: Long = 0L

    private var latestDiscoveredRssi: Int = 0

    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var tickerJob: Job? = null

    init {
        // Ticker job for cleaning up stale incomplete log assemblies every second
        tickerJob = scope.launch {
            while (true) {
                delay(1000)
                if (_connectionState.value == BleConnectionState.RECEIVING_TELEMETRY ||
                    _connectionState.value == BleConnectionState.CONNECTED_WAITING_FOR_DATA
                ) {
                    logReassembler.cleanStaleAssemblies()
                }
            }
        }
    }

    fun close() {
        tickerJob?.cancel()
        reconnectJob?.cancel()
        connectTimeoutJob?.cancel()
        serviceReadinessTimeoutJob?.cancel()
        healthySessionJob?.cancel()
        opTimeoutJob?.cancel()
    }

    fun hasLightingSupport(): Boolean {
        return hasConfigV2Support()
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName ?: device.name ?: PulseShiftBleProtocol.DEVICE_NAME
            val rssi = result.rssi
            val address = device.address

            val current = _discoveredDevices.value.toMutableList()
            val existingIndex = current.indexOfFirst { it.address == address }
            val updated = BleDevice(address = address, name = name, rssi = rssi)
            if (existingIndex >= 0) {
                current[existingIndex] = updated
            } else {
                current.add(updated)
            }
            _discoveredDevices.value = current
            if (address == activeGatt?.device?.address) {
                latestDiscoveredRssi = rssi
            }

            if (_connectionState.value == BleConnectionState.SCANNING && current.isNotEmpty()) {
                _connectionState.value = BleConnectionState.DEVICE_DISCOVERED
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _connectionState.value = BleConnectionState.ERROR
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            _connectionState.value = BleConnectionState.UNSUPPORTED
            return
        }
        if (!adapter.isEnabled) {
            _connectionState.value = BleConnectionState.BLUETOOTH_DISABLED
            return
        }

        _discoveredDevices.value = emptyList()
        _connectionState.value = BleConnectionState.SCANNING

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = BleConnectionState.ERROR
            return
        }

        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(PulseShiftBleProtocol.SERVICE_UUID)).build(),
            ScanFilter.Builder().setDeviceName(PulseShiftBleProtocol.DEVICE_NAME).build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "startScan denied: ${e.message}")
            _connectionState.value = BleConnectionState.PERMISSION_REQUIRED
            return
        }

        scope.launch {
            delay(10000)
            if (_connectionState.value == BleConnectionState.SCANNING || _connectionState.value == BleConnectionState.DEVICE_DISCOVERED) {
                stopScan()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan denied: ${e.message}")
        }
        if (_connectionState.value == BleConnectionState.SCANNING || _connectionState.value == BleConnectionState.DEVICE_DISCOVERED) {
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val adapter = bluetoothAdapter ?: run {
            _connectionState.value = BleConnectionState.UNSUPPORTED
            return
        }
        if (!adapter.isEnabled) {
            _connectionState.value = BleConnectionState.BLUETOOTH_DISABLED
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: Exception) {
            null
        } ?: run {
            _connectionError.value = "Invalid device address: $address"
            _connectionState.value = BleConnectionState.ERROR
            return
        }

        stopScan()
        userInitiatedDisconnect = false
        lastConnectedAddress = address
        reconnectAttempts = 0
        _isReconnecting.value = false
        _connectionError.value = null
        _connectionState.value = BleConnectionState.CONNECTING

        activeGatt?.close()
        activeGatt = null
        val gatt = try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.w(TAG, "connectGatt denied: ${e.message}")
            null
        }
        if (gatt == null) {
            _connectionState.value = BleConnectionState.PERMISSION_REQUIRED
            return
        }
        activeGatt = gatt

        // Independent connection timeout job per attempt
        startConnectTimeoutJob(address)
    }

    private fun startConnectTimeoutJob(address: String) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(15_000L)
            if (_connectionState.value == BleConnectionState.CONNECTING ||
                _connectionState.value == BleConnectionState.DISCOVERING_SERVICES ||
                _connectionState.value == BleConnectionState.SUBSCRIBING ||
                (_connectionState.value == BleConnectionState.CONNECTED_WAITING_FOR_DATA && lastUptimeMs == 0L)
            ) {
                Log.w(TAG, "Connection attempt to $address timed out after 15s")
                val uptimeSec = if (lastUptimeMs > 0) "${lastUptimeMs / 1000}s" else "unknown"
                val timestamp = System.currentTimeMillis()
                logDiagnosticEvent(
                    "Connection attempt timed out after 15s to $address at $timestamp (ESP32 uptime: $uptimeSec)",
                    LogSeverity.WARNING
                )
                teardownTransport(wasUserInitiated = false)
                _connectionState.value = BleConnectionState.ERROR
                if (autoReconnectEnabled && !userInitiatedDisconnect) {
                    scheduleReconnect("Connection timed out after 15s.")
                } else {
                    _connectionError.value = "Connection timed out after 15 s. Check that the device is powered and in range."
                    _isReconnecting.value = false
                }
            }
        }
    }

    private fun cancelServiceReadinessTimeout() {
        serviceReadinessTimeoutJob?.cancel()
        serviceReadinessTimeoutJob = null
    }

    private fun startServiceReadinessTimeoutJob(gatt: BluetoothGatt) {
        serviceReadinessTimeoutJob?.cancel()
        serviceReadinessTimeoutJob = scope.launch {
            delay(15_000L)
            if (activeGatt == gatt &&
                _connectionState.value != BleConnectionState.RECEIVING_TELEMETRY &&
                _connectionState.value != BleConnectionState.READY &&
                _connectionState.value != BleConnectionState.CONNECTED_WAITING_FOR_DATA
            ) {
                val devAddr = gatt.device?.address ?: lastConnectedAddress ?: "unknown"
                Log.w(TAG, "Service discovery / readiness timed out after 15s for $devAddr (state=${_connectionState.value})")
                logDiagnosticEvent("Service discovery / readiness timed out after 15s for $devAddr", LogSeverity.WARNING)
                val errorDetail = "Service discovery / readiness timed out after 15 seconds"
                _connectionError.value = errorDetail
                _connectionState.value = BleConnectionState.ERROR
                teardownTransport(wasUserInitiated = false)
                if (autoReconnectEnabled && !userInitiatedDisconnect) {
                    scheduleReconnect(errorDetail)
                } else {
                    _isReconnecting.value = false
                }
            }
        }
    }

    /**
     * Teardown GATT connection, clear queues, and reset operational state.
     * Does NOT mark userInitiatedDisconnect = true unless called from user disconnect().
     */
    private fun teardownTransport(wasUserInitiated: Boolean) {
        cancelServiceReadinessTimeout()
        activeGatt?.disconnect()
        activeGatt?.close()
        activeGatt = null
        synchronized(this) {
            gattOpQueue.clear()
            activeGattOp = null
            isGattOpPending = false
            opTimeoutJob?.cancel()
            opTimeoutJob = null
        }
        isHistoricalRetrievalActive = false
        isCaptureRetrievalActive = false
        isV2CapabilitiesValidated = false
        _connectedDevice.value = null
        _rpmTelemetryState.value = null
        _canHealthState.value = null
        _configSnapshots.value = null
        lastUptimeMs = 0L
        logReassembler.clear()
        snapshotReassembler.clear()
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
        cancelServiceReadinessTimeout()
        healthySessionJob?.cancel()
        healthySessionJob = null
        _isReconnecting.value = false
        connectedSinceMs = 0L
        teardownTransport(wasUserInitiated = true)
        _connectionState.value = BleConnectionState.DISCONNECTED
        _connectionError.value = null
    }

    @androidx.annotation.VisibleForTesting
    internal fun isUserInitiatedDisconnect(): Boolean = userInitiatedDisconnect

    @androidx.annotation.VisibleForTesting
    internal fun getReconnectAttempts(): Int = reconnectAttempts

    @androidx.annotation.VisibleForTesting
    internal fun getLastConnectedAddress(): String? = lastConnectedAddress

    @androidx.annotation.VisibleForTesting
    internal fun getActiveGatt(): BluetoothGatt? = activeGatt

    @androidx.annotation.VisibleForTesting
    internal fun getGattCallback(): BluetoothGattCallback = gattCallback

    @androidx.annotation.VisibleForTesting
    internal fun getGattOpQueueSize(): Int = synchronized(this) { gattOpQueue.size }

    @androidx.annotation.VisibleForTesting
    internal fun isGattOpPending(): Boolean = synchronized(this) { isGattOpPending }

    @androidx.annotation.VisibleForTesting
    internal fun setConnectionStateForTesting(state: BleConnectionState) {
        _connectionState.value = state
    }

    @androidx.annotation.VisibleForTesting
    internal fun setActiveGattForTesting(gatt: BluetoothGatt?) {
        activeGatt = gatt
    }

    @androidx.annotation.VisibleForTesting
    internal fun setLastConnectedAddressForTesting(address: String?) {
        lastConnectedAddress = address
    }

    @androidx.annotation.VisibleForTesting
    internal fun setConnectedSinceMsForTesting(timeMs: Long) {
        connectedSinceMs = timeMs
    }

    @androidx.annotation.VisibleForTesting
    internal fun setReconnectAttemptsForTesting(attempts: Int) {
        reconnectAttempts = attempts
    }

    @androidx.annotation.VisibleForTesting
    internal fun setUserInitiatedDisconnectForTesting(userInitiated: Boolean) {
        userInitiatedDisconnect = userInitiated
    }

    @androidx.annotation.VisibleForTesting
    internal fun triggerScheduleReconnectForTesting(errorDetail: String? = null) {
        scheduleReconnect(errorDetail)
    }

    @androidx.annotation.VisibleForTesting
    internal fun triggerTeardownTransportForTesting(wasUserInitiated: Boolean) {
        teardownTransport(wasUserInitiated)
    }

    @androidx.annotation.VisibleForTesting
    internal fun enqueueGattOpForTesting(op: GattOp) {
        enqueueGattOp(op)
    }

    @androidx.annotation.VisibleForTesting
    internal fun getServiceReadinessTimeoutJob(): Job? = serviceReadinessTimeoutJob

    @androidx.annotation.VisibleForTesting
    internal fun triggerStartServiceReadinessTimeoutJobForTesting(gatt: BluetoothGatt) {
        startServiceReadinessTimeoutJob(gatt)
    }

    @androidx.annotation.VisibleForTesting
    internal fun triggerCancelServiceReadinessTimeoutForTesting() {
        cancelServiceReadinessTimeout()
    }

    @androidx.annotation.VisibleForTesting
    internal fun triggerServiceReadinessTimeoutImmediatelyForTesting(gatt: BluetoothGatt) {
        if (activeGatt == gatt &&
            _connectionState.value != BleConnectionState.RECEIVING_TELEMETRY &&
            _connectionState.value != BleConnectionState.READY &&
            _connectionState.value != BleConnectionState.CONNECTED_WAITING_FOR_DATA
        ) {
            val devAddr = gatt.device?.address ?: lastConnectedAddress ?: "unknown"
            logDiagnosticEvent("Service discovery / readiness timed out after 15s for $devAddr", LogSeverity.WARNING)
            val errorDetail = "Service discovery / readiness timed out after 15 seconds"
            _connectionError.value = errorDetail
            _connectionState.value = BleConnectionState.ERROR
            teardownTransport(wasUserInitiated = false)
            if (autoReconnectEnabled && !userInitiatedDisconnect) {
                scheduleReconnect(errorDetail)
            } else {
                _isReconnecting.value = false
            }
        }
    }

    private var isV2CapabilitiesValidated = false

    fun hasConfigV2Support(): Boolean {
        if (!isV2CapabilitiesValidated) return false
        val cmd = configCmdCharacteristic ?: return false
        val res = configResultCharacteristic ?: return false
        val snap = configSnapshotCharacteristic ?: return false
        val caps = capabilitiesCharacteristic ?: return false

        val cmdWrite = (cmd.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
        val resNotify = (res.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ)) != 0
        val snapNotify = (snap.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        val capsRead = (caps.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0

        return cmdWrite && resNotify && snapNotify && capsRead
    }

    /**
     * Enqueue Configuration V2 Command (Characteristic fe12d215-...).
     * Returns false when no V2 command characteristic exists: no synthetic
     * ACK is fabricated — callers awaiting a real ACK will time out and
     * surface the honest failure.
     */
    fun enqueueConfigCommand(commandBytes: ByteArray): Boolean {
        val char = configCmdCharacteristic ?: run {
            Log.w(TAG, "Attempted V2 config command without a V2 command characteristic")
            return false
        }
        enqueueGattOp(GattOp.WriteChar(char, commandBytes))
        return true
    }

    /**
     * Send Control Request to fetch historical log page (Routes strictly to `controlCharacteristic`).
     */
    fun requestHistoricalLogPage(startOffset: Long) {
        val char = controlCharacteristic ?: return
        isHistoricalRetrievalActive = true
        val frame = PulseShiftBleProtocol.buildFetchHistoricalLogPage(startOffset)
        enqueueGattOp(GattOp.WriteChar(char, frame))
    }

    /**
     * Send Control Request to cancel historical log retrieval.
     */
    fun cancelHistoricalLogRetrieval() {
        val char = controlCharacteristic
        isHistoricalRetrievalActive = false
        if (char != null) {
            val frame = PulseShiftBleProtocol.buildCancelHistoricalLogRetrieval()
            enqueueGattOp(GattOp.WriteChar(char, frame))
        }
    }

    /**
     * Send Control Request to fetch a bounded page of CAN capture records
     * (BLE_CTRL_CMD_GET_CAPTURE_PAGE). Records stream over the log
     * characteristic with the CAPTURE_LINE flag.
     */
    fun requestHistoricalCapturePage(startRecord: Long) {
        val char = controlCharacteristic ?: return
        isCaptureRetrievalActive = true
        val frame = PulseShiftBleProtocol.buildFetchCapturePage(startRecord)
        enqueueGattOp(GattOp.WriteChar(char, frame))
    }

    /**
     * Send Control Request to cancel an ongoing CAN capture page transfer.
     */
    fun cancelCaptureRetrieval() {
        val char = controlCharacteristic
        isCaptureRetrievalActive = false
        if (char != null) {
            val frame = PulseShiftBleProtocol.buildCancelCaptureRetrieval()
            enqueueGattOp(GattOp.WriteChar(char, frame))
        }
    }

    /**
     * Enqueues a GATT operation to the unified serialized queue.
     */
    @Synchronized
    private fun enqueueGattOp(op: GattOp) {
        gattOpQueue.offer(op)
        dispatchNextGattOp()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun dispatchNextGattOp() {
        if (isGattOpPending) return
        val op = gattOpQueue.poll() ?: return
        val gatt = activeGatt
        if (gatt == null) {
            isGattOpPending = false
            activeGattOp = null
            return
        }

        activeGattOp = op
        isGattOpPending = true

        // Timeout fallback if callback drops (5000ms)
        opTimeoutJob?.cancel()
        opTimeoutJob = scope.launch {
            delay(5000)
            synchronized(this@PulseShiftBleManager) {
                if (isGattOpPending && activeGattOp == op) {
                    isGattOpPending = false
                    activeGattOp = null
                    dispatchNextGattOp()
                }
            }
        }

        var initiated = false
        when (op) {
            is GattOp.ReadChar -> {
                initiated = gatt.readCharacteristic(op.characteristic)
                Log.d(TAG, "dispatchNextGattOp: ReadChar for ${op.characteristic.uuid}, initiated=$initiated")
            }
            is GattOp.WriteChar -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val status = gatt.writeCharacteristic(op.characteristic, op.value, op.writeType)
                    initiated = (status == BluetoothStatusCodes.SUCCESS)
                    Log.d(TAG, "dispatchNextGattOp: WriteChar (Tiramisu+) status=$status for ${op.characteristic.uuid}, initiated=$initiated")
                } else {
                    @Suppress("DEPRECATION")
                    op.characteristic.value = op.value
                    @Suppress("DEPRECATION")
                    op.characteristic.writeType = op.writeType
                    @Suppress("DEPRECATION")
                    initiated = gatt.writeCharacteristic(op.characteristic)
                    Log.d(TAG, "dispatchNextGattOp: WriteChar (legacy) initiated=$initiated for ${op.characteristic.uuid}")
                }
            }
            is GattOp.WriteDesc -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val status = gatt.writeDescriptor(op.descriptor, op.value)
                    initiated = (status == BluetoothStatusCodes.SUCCESS)
                    Log.d(TAG, "dispatchNextGattOp: WriteDesc (Tiramisu+) status=$status for desc ${op.descriptor.uuid}, initiated=$initiated")
                } else {
                    @Suppress("DEPRECATION")
                    op.descriptor.value = op.value
                    @Suppress("DEPRECATION")
                    initiated = gatt.writeDescriptor(op.descriptor)
                    Log.d(TAG, "dispatchNextGattOp: WriteDesc (legacy) initiated=$initiated for desc ${op.descriptor.uuid}")
                }
            }
            is GattOp.EnableNotification -> {
                val char = op.characteristic
                val localSuccess = gatt.setCharacteristicNotification(char, op.enable)
                Log.d(TAG, "setCharacteristicNotification(${char.uuid}, ${op.enable}) -> localSuccess=$localSuccess")
                if (!localSuccess) {
                    Log.e(TAG, "Failed local notification registration for ${char.uuid}")
                    initiated = false
                } else {
                    val desc = char.getDescriptor(CCCD_UUID)
                    if (desc == null) {
                        Log.e(TAG, "CCCD descriptor (0x2902) missing for ${char.uuid}")
                        initiated = false
                    } else {
                        val payload = if (op.enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val status = gatt.writeDescriptor(desc, payload)
                            initiated = (status == BluetoothStatusCodes.SUCCESS)
                            Log.d(TAG, "writeDescriptor (Tiramisu+) for CCCD ${char.uuid}: status=$status, initiated=$initiated")
                        } else {
                            @Suppress("DEPRECATION")
                            desc.value = payload
                            @Suppress("DEPRECATION")
                            initiated = gatt.writeDescriptor(desc)
                            Log.d(TAG, "writeDescriptor (legacy) for CCCD ${char.uuid}: initiated=$initiated")
                        }
                    }
                }
            }
            is GattOp.ReadRssi -> {
                initiated = gatt.readRemoteRssi()
                Log.d(TAG, "dispatchNextGattOp: ReadRssi, initiated=$initiated")
            }
        }

        if (!initiated) {
            opTimeoutJob?.cancel()
            isGattOpPending = false
            activeGattOp = null
            dispatchNextGattOp()
        }
    }

    @Synchronized
    private fun onGattOpCompleted() {
        opTimeoutJob?.cancel()
        opTimeoutJob = null
        isGattOpPending = false
        activeGattOp = null
        dispatchNextGattOp()
    }

    /**
     * Publish the connected device from transport facts only. Real
     * capabilities arrive via the V2 capabilities characteristic read; until
     * then this only reports which characteristics exist — no fabricated
     * feature flags, no invented RSSI.
     */
    @SuppressLint("MissingPermission")
    private fun publishConnectedDevice() {
        val deviceAddress = activeGatt?.device?.address ?: return
        val deviceName = activeGatt?.device?.name ?: PulseShiftBleProtocol.DEVICE_NAME
        val hasV2 = hasConfigV2Support()

        val existing = _connectedDevice.value
        // Keep authoritative capabilities once validated; never overwrite them
        // with synthetic defaults on every telemetry frame.
        val caps = existing?.capabilities?.takeIf { it.hasConfigV2Support }
            ?: DeviceCapabilities(
                physicalLedCount = if (hasV2 || rpmCharacteristic != null) 6 else 0,
                hasConfigV2Support = hasV2,
                supportsLiveRpmVerified =
                    rpmTelemetryState.value?.statusFlags?.isCalibrationVerified == true
            )

        _connectedDevice.value = BleDevice(
            address = deviceAddress,
            name = deviceName,
            rssi = latestDiscoveredRssi,
            isConnected = true,
            firmwareVersion = existing?.firmwareVersion
                ?: if (hasV2) "2.x (Config V2)" else null,
            hardwareRevision = existing?.hardwareRevision,
            capabilities = caps
        )
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt != activeGatt) {
                Log.w(TAG, "Ignoring onConnectionStateChange for stale GATT instance ($gatt)")
                try { gatt.close() } catch (_: Exception) {}
                return
            }

            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectTimeoutJob?.cancel()
                connectTimeoutJob = null
                _isReconnecting.value = false
                connectedSinceMs = System.currentTimeMillis()
                _connectionState.value = BleConnectionState.DISCOVERING_SERVICES
                gatt.discoverServices()
                startServiceReadinessTimeoutJob(gatt)

                healthySessionJob?.cancel()
                healthySessionJob = scope.launch {
                    delay(30_000L)
                    if (activeGatt == gatt) {
                        reconnectAttempts = 0
                        Log.d(TAG, "Connection healthy for >30s; reset reconnectAttempts to 0")
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                healthySessionJob?.cancel()
                healthySessionJob = null
                val wasUserInitiated = userInitiatedDisconnect
                val wasHealthy = (connectedSinceMs > 0 && (System.currentTimeMillis() - connectedSinceMs > 30_000L))
                if (wasHealthy) {
                    reconnectAttempts = 0
                }
                connectedSinceMs = 0L

                val devAddr = gatt.device?.address ?: lastConnectedAddress ?: "unknown"
                val uptimeSec = if (lastUptimeMs > 0) "${lastUptimeMs / 1000}s" else "unknown"
                val statusStr = gattStatusToString(status)
                val timestamp = System.currentTimeMillis()

                teardownTransport(wasUserInitiated = wasUserInitiated)

                if (wasUserInitiated) {
                    logDiagnosticEvent(
                        "User-initiated disconnect from $devAddr at $timestamp (status: $statusStr, ESP32 uptime: $uptimeSec)",
                        LogSeverity.INFO
                    )
                    _connectionError.value = null
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    _isReconnecting.value = false
                } else {
                    logDiagnosticEvent(
                        "Unexpected transport disconnect from $devAddr at $timestamp (status: $statusStr, ESP32 uptime: $uptimeSec)",
                        LogSeverity.WARNING
                    )
                    val errorDetail = explainGattStatus(status)
                    _connectionState.value = BleConnectionState.DISCONNECTED

                    if (autoReconnectEnabled) {
                        scheduleReconnect(errorDetail)
                    } else {
                        _connectionError.value = errorDetail
                        _isReconnecting.value = false
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt != activeGatt) {
                Log.w(TAG, "Ignoring onServicesDiscovered for stale GATT instance ($gatt)")
                return
            }

            Log.d(TAG, "onServicesDiscovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val errorDetail = "Service discovery failed (GATT status $status: ${explainGattStatus(status)})."
                _connectionError.value = errorDetail
                _connectionState.value = BleConnectionState.ERROR
                logDiagnosticEvent(errorDetail, LogSeverity.ERROR)
                teardownTransport(wasUserInitiated = false)
                if (autoReconnectEnabled && !userInitiatedDisconnect) {
                    scheduleReconnect(errorDetail)
                }
                return
            }

            val service = gatt.getService(PulseShiftBleProtocol.SERVICE_UUID)

            if (service == null) {
                Log.e(TAG, "Target service not found on device!")
                _connectionError.value = "PulseShift service not found. This is not PulseShift firmware."
                _connectionState.value = BleConnectionState.ERROR
                disconnect()
                return
            }

            Log.d(TAG, "Discovered service UUID: ${service.uuid}")
            for (c in service.characteristics) {
                Log.d(TAG, "  Discovered char UUID: ${c.uuid}, props=${c.properties}")
            }

            // Primary Protocol V1 Characteristics
            rpmCharacteristic = service.getCharacteristic(PulseShiftBleProtocol.RPM_CHAR_UUID)
            healthCharacteristic = service.getCharacteristic(PulseShiftBleProtocol.HEALTH_CHAR_UUID)
            logCharacteristic = service.getCharacteristic(PulseShiftBleProtocol.LOG_CHAR_UUID)
            controlCharacteristic = service.getCharacteristic(PulseShiftBleProtocol.CONTROL_CHAR_UUID)

            // Protocol V2 Characteristics (All 4 must be present for full V2 support)
            val cmdChar = service.getCharacteristic(PulseShiftBleProtocol.CONFIG_CMD_CHAR_UUID)
            val resChar = service.getCharacteristic(PulseShiftBleProtocol.CONFIG_RESULT_CHAR_UUID)
            val snapChar = service.getCharacteristic(PulseShiftBleProtocol.CONFIG_SNAPSHOT_CHAR_UUID)
            val capsChar = service.getCharacteristic(PulseShiftBleProtocol.CAPABILITIES_CHAR_UUID)

            if (cmdChar != null && resChar != null && snapChar != null && capsChar != null) {
                configCmdCharacteristic = cmdChar
                configResultCharacteristic = resChar
                configSnapshotCharacteristic = snapChar
                capabilitiesCharacteristic = capsChar
            } else {
                configCmdCharacteristic = null
                configResultCharacteristic = null
                configSnapshotCharacteristic = null
                capabilitiesCharacteristic = null
            }

            _connectionState.value = BleConnectionState.SUBSCRIBING

            // Enqueue separate, verified notification subscriptions via GATT Op Queue
            rpmCharacteristic?.let { enqueueGattOp(GattOp.EnableNotification(it)) }
            healthCharacteristic?.let { enqueueGattOp(GattOp.EnableNotification(it)) }
            logCharacteristic?.let { enqueueGattOp(GattOp.EnableNotification(it)) }
            configResultCharacteristic?.let { enqueueGattOp(GattOp.EnableNotification(it)) }
            configSnapshotCharacteristic?.let { enqueueGattOp(GattOp.EnableNotification(it)) }

            isV2CapabilitiesValidated = false

            // Initial reads
            rpmCharacteristic?.let { enqueueGattOp(GattOp.ReadChar(it)) }
            healthCharacteristic?.let { enqueueGattOp(GattOp.ReadChar(it)) }
            capabilitiesCharacteristic?.let { enqueueGattOp(GattOp.ReadChar(it)) }
            enqueueGattOp(GattOp.ReadRssi)

            publishConnectedDevice()
            _connectionState.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt != activeGatt) {
                Log.w(TAG, "Ignoring onDescriptorWrite for stale GATT instance")
                return
            }
            val charUuid = descriptor.characteristic?.uuid
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // A failed CCCD write means notifications for this
                // characteristic will never arrive; surface it loudly.
                Log.e(TAG, "Descriptor write FAILED ($status) for $charUuid — subscription broken")
            } else {
                Log.d(TAG, "onDescriptorWrite ok for $charUuid")
            }
            onGattOpCompleted()
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (gatt != activeGatt) {
                Log.w(TAG, "Ignoring onReadRemoteRssi for stale GATT instance")
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                latestDiscoveredRssi = rssi
                val dev = _connectedDevice.value
                if (dev != null) {
                    _connectedDevice.value = dev.copy(rssi = rssi)
                }
            }
            onGattOpCompleted()
        }

        @SuppressLint("MissingPermission")
        private fun handleCharacteristicRead(uuid: UUID, value: ByteArray) {
            when (uuid) {
                PulseShiftBleProtocol.RPM_CHAR_UUID -> {
                    PulseShiftBleProtocol.parseRpmTelemetry(value)?.let { rpm ->
                        checkRebootAndEmitRpm(rpm)
                        cancelServiceReadinessTimeout()
                        _connectionState.value = BleConnectionState.RECEIVING_TELEMETRY
                    }
                }
                PulseShiftBleProtocol.HEALTH_CHAR_UUID -> {
                    PulseShiftBleProtocol.parseCanHealth(value)?.let { health ->
                        _canHealthState.value = health
                    }
                }
                PulseShiftBleProtocol.CAPABILITIES_CHAR_UUID -> {
                    val caps = PulseShiftBleProtocol.parseCapabilitiesV2(value)
                    if (caps != null && caps.hasConfigV2Support) {
                        isV2CapabilitiesValidated = true
                        val dev = _connectedDevice.value
                        if (dev != null) {
                            // Real, feature-mask-derived capabilities replace
                            // the transport-only placeholder — once.
                            _connectedDevice.value = dev.copy(
                                firmwareVersion = "v${caps.firmwareVersion} (Config V2)",
                                capabilities = caps
                            )
                        }
                        // Spontaneous snapshot fetch uses a fixed high sequence
                        // that cannot collide with the repository's 1..N counter.
                        if (configCmdCharacteristic != null) {
                            enqueueGattOp(
                                GattOp.WriteChar(
                                    configCmdCharacteristic!!,
                                    PulseShiftBleProtocol.buildGetSnapshotV2(SNAPSHOT_REQUEST_SEQ)
                                )
                            )
                        }
                        cancelServiceReadinessTimeout()
                        _connectionState.value = BleConnectionState.READY
                    } else {
                        isV2CapabilitiesValidated = false
                        publishConnectedDevice()
                    }
                }
                else -> {}
            }
        }

        private fun checkRebootAndEmitRpm(rpm: RpmTelemetryState) {
            if (lastUptimeMs > 0 && rpm.uptimeMs < (lastUptimeMs - 2000L)) {
                // Phone-side inference, clearly labeled as such — not a device log.
                logDiagnosticEvent(
                    "[phone] ESP32 restart inferred (uptime ${lastUptimeMs / 1000}s -> ${rpm.uptimeMs / 1000}s)",
                    LogSeverity.INFO
                )
            }
            lastUptimeMs = rpm.uptimeMs
            _rpmTelemetryState.value = rpm
        }

        private fun handleCharacteristicChanged(uuid: UUID, value: ByteArray) {
            if (BuildConfig.DEBUG) {
                val seqStr = when (uuid) {
                    PulseShiftBleProtocol.RPM_CHAR_UUID, PulseShiftBleProtocol.HEALTH_CHAR_UUID ->
                        if (value.size >= 4) {
                            "seq=" + ((value[2].toInt() and 0xFF) or ((value[3].toInt() and 0xFF) shl 8))
                        } else "seq=N/A"
                    PulseShiftBleProtocol.LOG_CHAR_UUID ->
                        if (value.size >= 5) {
                            "msgId=" + ((value[2].toInt() and 0xFF) or ((value[3].toInt() and 0xFF) shl 8)) +
                                " chunkIdx=" + (value[4].toInt() and 0xFF)
                        } else "seq=N/A"
                    else -> "seq=N/A"
                }
                Log.d(TAG, "onCharacteristicChanged: UUID=$uuid len=${value.size} $seqStr")
            }

            when (uuid) {
                PulseShiftBleProtocol.RPM_CHAR_UUID -> {
                    PulseShiftBleProtocol.parseRpmTelemetry(value)?.let { rpm ->
                        checkRebootAndEmitRpm(rpm)
                        cancelServiceReadinessTimeout()
                        _connectionState.value = BleConnectionState.RECEIVING_TELEMETRY
                    }
                }
                PulseShiftBleProtocol.HEALTH_CHAR_UUID -> {
                    PulseShiftBleProtocol.parseCanHealth(value)?.let { health ->
                        _canHealthState.value = health
                    }
                }
                PulseShiftBleProtocol.LOG_CHAR_UUID -> {
                    val chunk = logReassembler.processChunkBytes(value, isHistorical = isHistoricalRetrievalActive)
                    chunk?.let { _rawLogChunks.tryEmit(it) }
                }
                PulseShiftBleProtocol.CONFIG_RESULT_CHAR_UUID -> {
                    PulseShiftBleProtocol.parseConfigAck(value)?.let { ack ->
                        _configAcksV2.tryEmit(ack)
                    }
                }
                PulseShiftBleProtocol.CONFIG_SNAPSHOT_CHAR_UUID -> {
                    snapshotReassembler.processChunkBytes(value)?.let { snapshot ->
                        _configSnapshots.value = snapshot
                    }
                }
                else -> {}
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (gatt != activeGatt) {
                Log.w(TAG, "Ignoring onCharacteristicRead for stale GATT instance")
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicRead(characteristic.uuid, value)
            }
            onGattOpCompleted()
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (gatt != activeGatt) {
                Log.w(TAG, "Ignoring legacy onCharacteristicRead for stale GATT instance")
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val data = characteristic.value ?: return
                handleCharacteristicRead(characteristic.uuid, data)
            }
            onGattOpCompleted()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (gatt != activeGatt) {
                Log.w(TAG, "Ignoring onCharacteristicWrite for stale GATT instance")
                return
            }
            onGattOpCompleted()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (gatt != activeGatt) {
                Log.w(TAG, "Ignoring onCharacteristicChanged for stale GATT instance")
                return
            }
            handleCharacteristicChanged(characteristic.uuid, value)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (gatt != activeGatt) {
                Log.w(TAG, "Ignoring legacy onCharacteristicChanged for stale GATT instance")
                return
            }
            val data = characteristic.value ?: return
            handleCharacteristicChanged(characteristic.uuid, data)
        }
    }

    private fun createSynthLogBytes(text: String, severity: LogSeverity): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8).take(14).toByteArray()
        val pkg = ByteArray(6 + textBytes.size)
        pkg[0] = 1
        pkg[1] = (if (severity == LogSeverity.ERROR) 0x0B else 0x03).toByte() // First + Final + optional error
        pkg[2] = 0xFE.toByte()
        pkg[3] = 0xFF.toByte()
        pkg[4] = 0
        pkg[5] = textBytes.size.toByte()
        System.arraycopy(textBytes, 0, pkg, 6, textBytes.size)
        return pkg
    }
}
