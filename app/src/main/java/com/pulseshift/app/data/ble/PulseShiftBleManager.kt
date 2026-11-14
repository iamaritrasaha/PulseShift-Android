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
import com.pulseshift.app.domain.model.BleDevice
import com.pulseshift.app.domain.model.CanHealthState
import com.pulseshift.app.domain.model.DeviceCapabilities
import com.pulseshift.app.domain.model.DiagnosticLogChunk
import com.pulseshift.app.domain.model.DiagnosticLogMessage
import com.pulseshift.app.domain.model.HistoricalPageEnd
import com.pulseshift.app.domain.model.LightingState
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

class PulseShiftBleManager(val context: Context) {
    companion object {
        private const val TAG = "PulseShiftBle"
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

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

    // Legacy / Lighting State Updates
    private val _deviceStateUpdates = MutableSharedFlow<LightingState>(extraBufferCapacity = 1)
    val deviceStateUpdates: SharedFlow<LightingState> = _deviceStateUpdates.asSharedFlow()

    private val _commandAcks = MutableSharedFlow<PulseShiftBleProtocol.AckResponse>(extraBufferCapacity = 8)
    val commandAcks: SharedFlow<PulseShiftBleProtocol.AckResponse> = _commandAcks.asSharedFlow()

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

    // Legacy characteristics
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var stateCharacteristic: BluetoothGattCharacteristic? = null
    private var deviceInfoCharacteristic: BluetoothGattCharacteristic? = null

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
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private val _isReconnecting = kotlinx.coroutines.flow.MutableStateFlow(false)
    val isReconnecting: kotlinx.coroutines.flow.StateFlow<Boolean> = _isReconnecting

    fun setAutoReconnectEnabled(enabled: Boolean) {
        autoReconnectEnabled = enabled
        if (!enabled) {
            reconnectJob?.cancel()
            _isReconnecting.value = false
        }
    }

    private fun scheduleReconnect() {
        if (!autoReconnectEnabled) return
        val address = lastConnectedAddress ?: return
        if (reconnectAttempts >= 5) {
            _isReconnecting.value = false
            return
        }
        val attempt = ++reconnectAttempts
        _isReconnecting.value = true
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            // Linear backoff: 1s, 2s, 3s ... capped at 5s.
            delay(minOf(1000L * attempt, 5000L))
            if (!autoReconnectEnabled) return@launch
            val adapter = bluetoothAdapter ?: return@launch
            val device = try {
                adapter.getRemoteDevice(address)
            } catch (e: Exception) {
                null
            } ?: return@launch
            _connectionState.value = BleConnectionState.CONNECTING
            activeGatt?.close()
            activeGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    // Sequence & Reboot Tracking
    private var currentSequence: Int = 1
    private var lastUptimeMs: Long = 0L

    private var latestDiscoveredRssi: Int = 0

    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    init {
        // Ticker job for cleaning up stale incomplete log assemblies every second
        scope.launch {
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

    fun hasLightingSupport(): Boolean {
        return commandCharacteristic != null
    }

    @Synchronized
    fun nextSequence(): Byte {
        val s = currentSequence
        currentSequence = if (currentSequence >= 255) 1 else currentSequence + 1
        return s.toByte()
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
            ScanFilter.Builder().setServiceUuid(ParcelUuid(PulseShiftBleProtocol.LEGACY_SERVICE_UUID)).build(),
            ScanFilter.Builder().setDeviceName(PulseShiftBleProtocol.DEVICE_NAME).build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, scanCallback)

        scope.launch {
            delay(10000)
            if (_connectionState.value == BleConnectionState.SCANNING || _connectionState.value == BleConnectionState.DEVICE_DISCOVERED) {
                stopScan()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_connectionState.value == BleConnectionState.SCANNING || _connectionState.value == BleConnectionState.DEVICE_DISCOVERED) {
            _connectionState.value = BleConnectionState.DISCONNECTED
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val adapter = bluetoothAdapter ?: return
        val device = adapter.getRemoteDevice(address) ?: return

        stopScan()
        userInitiatedDisconnect = false
        lastConnectedAddress = address
        reconnectAttempts = 0
        _isReconnecting.value = false
        _connectionState.value = BleConnectionState.CONNECTING

        activeGatt?.close()
        activeGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        userInitiatedDisconnect = true
        reconnectJob?.cancel()
        _isReconnecting.value = false
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
        isV2CapabilitiesValidated = false
        _connectedDevice.value = null
        _rpmTelemetryState.value = null
        _canHealthState.value = null
        _configSnapshots.value = null
        lastUptimeMs = 0L
        logReassembler.clear()
        snapshotReassembler.clear()
        _connectionState.value = BleConnectionState.DISCONNECTED
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
     */
    fun enqueueConfigCommand(commandBytes: ByteArray) {
        val char = configCmdCharacteristic
        if (char == null) {
            Log.w(TAG, "Attempted V2 config command on non-V2 device")
            val seq = if (commandBytes.size >= 4) (commandBytes[2].toInt() and 0xFF) or ((commandBytes[3].toInt() and 0xFF) shl 8) else 0
            val opcode = if (commandBytes.size >= 2) commandBytes[1] else 0.toByte()
            _configAcksV2.tryEmit(
                com.pulseshift.app.domain.model.ConfigAckResponse(
                    version = 2,
                    opcode = opcode,
                    sequenceRef = seq,
                    statusCode = com.pulseshift.app.domain.model.ConfigAckResponse.STATUS_ERR_UNSUPPORTED.toByte(),
                    resultFlags = 0,
                    activeRevision = 0L
                )
            )
            return
        }
        enqueueGattOp(GattOp.WriteChar(char, commandBytes))
    }

    /**
     * Enqueue Legacy Lighting Command. Routes strictly to `commandCharacteristic`.
     */
    fun enqueueLightingCommand(commandBytes: ByteArray) {
        val char = commandCharacteristic
        if (char == null) {
            // Devices without legacy lighting service report UNSUPPORTED
            val seq = if (commandBytes.size > 2) commandBytes[2] else 0.toByte()
            _commandAcks.tryEmit(
                PulseShiftBleProtocol.AckResponse(
                    sequenceRef = seq,
                    statusCode = PulseShiftBleProtocol.STATUS_ERR_UNSUPPORTED,
                    isSuccess = false
                )
            )
            return
        }
        enqueueGattOp(GattOp.WriteChar(char, commandBytes))
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

    @SuppressLint("MissingPermission")
    private fun updateConnectedDeviceCapabilities() {
        val deviceAddress = activeGatt?.device?.address ?: ""
        val deviceName = activeGatt?.device?.name ?: PulseShiftBleProtocol.DEVICE_NAME
        val hasV2 = hasConfigV2Support()

        val parsedCaps = if (hasV2) {
            DeviceCapabilities(
                physicalLedCount = 6,
                hasConfigV2Support = true,
                supportsIndividualRgb = true,
                supportsEffects = true,
                supportsNvsPersistence = (healthCharacteristic != null),
                supportsStartupAnimation = true,
                supportsRpmThresholds = true,
                supportsShiftWarning = true,
                supportsMultiplier = true,
                supportsCandidateSource = true,
                supportsLiveRpmVerified = (rpmTelemetryState.value?.statusFlags?.isCalibrationVerified == true)
            )
        } else {
            DeviceCapabilities(
                physicalLedCount = if (healthCharacteristic != null) 6 else 0,
                hasConfigV2Support = false,
                supportsIndividualRgb = false,
                supportsEffects = false,
                supportsNvsPersistence = (healthCharacteristic != null),
                supportsStartupAnimation = false,
                supportsRpmThresholds = false,
                supportsShiftWarning = false,
                supportsMultiplier = false,
                supportsCandidateSource = false,
                supportsLiveRpmVerified = (rpmTelemetryState.value?.statusFlags?.isCalibrationVerified == true)
            )
        }

        val fwVer = parsedCaps?.firmwareVersion?.takeIf { it != "unknown" }
            ?: if (hasV2) "v2.0 (PulseShift Config V2)" else "v1.0 (PulseShift BLE Monitoring)"
        val hwRev = if (hasV2) "ESP32 DevKit V1 (Config V2)" else "ESP32 (CAN Monitor)"

        _connectedDevice.value = BleDevice(
            address = deviceAddress,
            name = deviceName,
            rssi = if (latestDiscoveredRssi != 0) latestDiscoveredRssi else -60,
            isConnected = true,
            firmwareVersion = fwVer,
            hardwareRevision = hwRev,
            capabilities = parsedCaps
        )
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = BleConnectionState.DISCOVERING_SERVICES
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val wasUserInitiated = userInitiatedDisconnect
                disconnect()
                if (!wasUserInitiated) {
                    // Unexpected drop (bike power-off is a normal case):
                    // automatically try to reconnect when the user wants it.
                    scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            val service = gatt.getService(PulseShiftBleProtocol.SERVICE_UUID)
                ?: gatt.getService(PulseShiftBleProtocol.LEGACY_SERVICE_UUID)

            if (service == null) {
                Log.e(TAG, "Target service not found on device!")
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

            // Legacy / Lighting Characteristics
            commandCharacteristic = service.getCharacteristic(PulseShiftBleProtocol.COMMAND_CHAR_UUID)
            stateCharacteristic = service.getCharacteristic(PulseShiftBleProtocol.STATE_CHAR_UUID)
            deviceInfoCharacteristic = service.getCharacteristic(PulseShiftBleProtocol.DEVICE_INFO_CHAR_UUID)

            _connectionState.value = BleConnectionState.SUBSCRIBING

            // Enqueue separate, verified notification subscriptions via GATT Op Queue
            rpmCharacteristic?.let { enqueueGattOp(GattOp.EnableNotification(it)) }
            healthCharacteristic?.let { enqueueGattOp(GattOp.EnableNotification(it)) }
            logCharacteristic?.let { enqueueGattOp(GattOp.EnableNotification(it)) }
            stateCharacteristic?.let { enqueueGattOp(GattOp.EnableNotification(it)) }
            configResultCharacteristic?.let { enqueueGattOp(GattOp.EnableNotification(it)) }
            configSnapshotCharacteristic?.let { enqueueGattOp(GattOp.EnableNotification(it)) }

            isV2CapabilitiesValidated = false

            // Initial reads
            rpmCharacteristic?.let { enqueueGattOp(GattOp.ReadChar(it)) }
            healthCharacteristic?.let { enqueueGattOp(GattOp.ReadChar(it)) }
            deviceInfoCharacteristic?.let { enqueueGattOp(GattOp.ReadChar(it)) }
            capabilitiesCharacteristic?.let { enqueueGattOp(GattOp.ReadChar(it)) }
            enqueueGattOp(GattOp.ReadRssi)

            updateConnectedDeviceCapabilities()
            _connectionState.value = BleConnectionState.CONNECTED_WAITING_FOR_DATA
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val charUuid = descriptor.characteristic?.uuid
            val isSuccess = status == BluetoothGatt.GATT_SUCCESS
            Log.d(TAG, "onDescriptorWrite callback: char=$charUuid status=$status success=$isSuccess")
            onGattOpCompleted()
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
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
                        _connectionState.value = BleConnectionState.RECEIVING_TELEMETRY
                    }
                }
                PulseShiftBleProtocol.HEALTH_CHAR_UUID -> {
                    PulseShiftBleProtocol.parseCanHealth(value)?.let { health ->
                        _canHealthState.value = health
                    }
                }
                PulseShiftBleProtocol.DEVICE_INFO_CHAR_UUID -> {
                    PulseShiftBleProtocol.parseDeviceInfo(value)?.let { (fwVersion, capabilities) ->
                        val dev = _connectedDevice.value
                        if (dev != null) {
                            _connectedDevice.value = dev.copy(firmwareVersion = fwVersion, capabilities = capabilities)
                        }
                    }
                }
                PulseShiftBleProtocol.CAPABILITIES_CHAR_UUID -> {
                    val caps = PulseShiftBleProtocol.parseCapabilitiesV2(value)
                    if (caps != null && caps.hasConfigV2Support) {
                        isV2CapabilitiesValidated = true
                        val dev = _connectedDevice.value
                        if (dev != null) {
                            _connectedDevice.value = dev.copy(
                                firmwareVersion = "v2.0 (PulseShift Config V2)",
                                capabilities = caps
                            )
                        }
                        if (configCmdCharacteristic != null) {
                            val seq = (1..65535).random()
                            enqueueGattOp(GattOp.WriteChar(configCmdCharacteristic!!, PulseShiftBleProtocol.buildGetSnapshotV2(seq)))
                        }
                    } else {
                        isV2CapabilitiesValidated = false
                        updateConnectedDeviceCapabilities()
                    }
                }
            }
        }

        private fun checkRebootAndEmitRpm(rpm: RpmTelemetryState) {
            if (lastUptimeMs > 0 && rpm.uptimeMs < (lastUptimeMs - 2000L)) {
                logReassembler.processChunkBytes(
                    createSynthLogBytes("ESP32 reboot detected (Uptime reset: $lastUptimeMs ms -> ${rpm.uptimeMs} ms)", LogSeverity.INFO)
                )
            }
            lastUptimeMs = rpm.uptimeMs
            _rpmTelemetryState.value = rpm
            updateConnectedDeviceCapabilities()
        }

        private fun handleCharacteristicChanged(uuid: UUID, value: ByteArray) {
            val hexString = value.joinToString(" ") { "%02X".format(it) }
            val seqStr = when (uuid) {
                PulseShiftBleProtocol.RPM_CHAR_UUID -> {
                    if (value.size >= 4) {
                        val seq = (value[2].toInt() and 0xFF) or ((value[3].toInt() and 0xFF) shl 8)
                        "seq=$seq"
                    } else "seq=N/A"
                }
                PulseShiftBleProtocol.HEALTH_CHAR_UUID -> {
                    if (value.size >= 4) {
                        val seq = (value[2].toInt() and 0xFF) or ((value[3].toInt() and 0xFF) shl 8)
                        "seq=$seq"
                    } else "seq=N/A"
                }
                PulseShiftBleProtocol.LOG_CHAR_UUID -> {
                    if (value.size >= 5) {
                        val msgId = (value[2].toInt() and 0xFF) or ((value[3].toInt() and 0xFF) shl 8)
                        val chunkIdx = value[4].toInt() and 0xFF
                        "msgId=$msgId chunkIdx=$chunkIdx"
                    } else "seq=N/A"
                }
                else -> "seq=N/A"
            }

            Log.d(TAG, "onCharacteristicChanged: UUID=$uuid len=${value.size} $seqStr raw=[$hexString]")

            when (uuid) {
                PulseShiftBleProtocol.RPM_CHAR_UUID -> {
                    PulseShiftBleProtocol.parseRpmTelemetry(value)?.let { rpm ->
                        checkRebootAndEmitRpm(rpm)
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
                PulseShiftBleProtocol.STATE_CHAR_UUID -> {
                    if (value.isNotEmpty() && value[1] == PulseShiftBleProtocol.FRAME_TYPE_ACK) {
                        PulseShiftBleProtocol.parseAck(value)?.let { ack ->
                            _commandAcks.tryEmit(ack)
                        }
                    } else if (value.isNotEmpty() && value[1] == PulseShiftBleProtocol.FRAME_TYPE_STATE) {
                        PulseShiftBleProtocol.parseState(value)?.let { state ->
                            _deviceStateUpdates.tryEmit(state)
                        }
                    }
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicRead(characteristic.uuid, value)
            }
            onGattOpCompleted()
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val data = characteristic.value ?: return
                handleCharacteristicRead(characteristic.uuid, data)
            }
            onGattOpCompleted()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            onGattOpCompleted()
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChanged(characteristic.uuid, value)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
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
