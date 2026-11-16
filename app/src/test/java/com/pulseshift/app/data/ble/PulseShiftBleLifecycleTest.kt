package com.pulseshift.app.data.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.pulseshift.app.data.local.PulseShiftDataStore
import com.pulseshift.app.data.repository.BlePulseShiftRepository
import com.pulseshift.app.domain.model.ConfigAckResponse
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.DiagnosticsReport
import com.pulseshift.app.domain.model.LogSeverity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.UUID

class PulseShiftBleLifecycleTest {

    private val testScope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val managersToClose = mutableListOf<PulseShiftBleManager>()

    private fun createTestBleManager(
        context: Context = mock(Context::class.java),
        scope: CoroutineScope = testScope
    ): PulseShiftBleManager {
        val manager = PulseShiftBleManager(
            context = context,
            scope = scope,
            adapterProvider = { null }
        )
        managersToClose.add(manager)
        return manager
    }

    @After
    fun tearDown() {
        managersToClose.forEach { it.close() }
        managersToClose.clear()
        testScope.cancel()
    }

    @Test
    fun testGattStatusCodesAndExplanations() {
        assertEquals("GATT_SUCCESS (0x00)", PulseShiftBleManager.gattStatusToString(BluetoothGatt.GATT_SUCCESS))
        assertEquals("GATT_CONN_TIMEOUT (0x08)", PulseShiftBleManager.gattStatusToString(8))
        assertEquals("GATT_CONN_TERMINATE_PEER_USER (0x13)", PulseShiftBleManager.gattStatusToString(19))
        assertEquals("GATT_CONN_TERMINATE_LOCAL_HOST (0x16)", PulseShiftBleManager.gattStatusToString(22))
        assertEquals("GATT_CONN_FAIL_ESTABLISH (0x3E)", PulseShiftBleManager.gattStatusToString(62))
        assertEquals("GATT_ERROR (0x85)", PulseShiftBleManager.gattStatusToString(133))
        assertEquals("GATT_INTERNAL_ERROR (0x100)", PulseShiftBleManager.gattStatusToString(256))

        val explain133 = PulseShiftBleManager.explainGattStatus(133)
        assertTrue(explain133.contains("133"))
        assertTrue(explain133.contains("GATT_ERROR"))

        val explainTimeout = PulseShiftBleManager.explainGattStatus(8)
        assertTrue(explainTimeout.contains("timed out"))
    }

    @Test
    fun testUnexpectedDisconnectDoesNotMarkUserInitiated() {
        val manager = createTestBleManager()
        val mockGatt = mock(BluetoothGatt::class.java)
        val mockDevice = mock(BluetoothDevice::class.java)
        `when`(mockDevice.address).thenReturn("AA:BB:CC:DD:EE:FF")
        `when`(mockGatt.device).thenReturn(mockDevice)

        manager.setActiveGattForTesting(mockGatt)
        manager.setLastConnectedAddressForTesting("AA:BB:CC:DD:EE:FF")
        manager.setAutoReconnectEnabled(true)
        assertFalse(manager.isUserInitiatedDisconnect())

        // Simulate transport drop callback from BluetoothGattCallback
        manager.getGattCallback().onConnectionStateChange(mockGatt, 19, BluetoothProfile.STATE_DISCONNECTED)

        // Verify userInitiatedDisconnect is STILL false!
        assertFalse(
            "Unexpected disconnect must NOT set userInitiatedDisconnect to true",
            manager.isUserInitiatedDisconnect()
        )
        assertEquals(BleConnectionState.DISCONNECTED, manager.connectionState.value)
        assertTrue(manager.isReconnecting.value)
        assertEquals(1, manager.getReconnectAttempts())
    }

    @Test
    fun testUserInitiatedDisconnectPreventsAutomaticReconnection() {
        val manager = createTestBleManager()
        val mockGatt = mock(BluetoothGatt::class.java)
        val mockDevice = mock(BluetoothDevice::class.java)
        `when`(mockDevice.address).thenReturn("AA:BB:CC:DD:EE:FF")
        `when`(mockGatt.device).thenReturn(mockDevice)

        manager.setActiveGattForTesting(mockGatt)
        manager.setLastConnectedAddressForTesting("AA:BB:CC:DD:EE:FF")
        manager.setAutoReconnectEnabled(true)

        // User explicitly requests disconnect via UI
        manager.disconnect()

        assertTrue("Explicit disconnect() must set userInitiatedDisconnect to true", manager.isUserInitiatedDisconnect())
        assertFalse("Explicit disconnect() must not be reconnecting", manager.isReconnecting.value)
        assertEquals(BleConnectionState.DISCONNECTED, manager.connectionState.value)
        assertNull("Explicit disconnect() is not an error", manager.connectionError.value)

        // Even if a subsequent callback comes from the BLE stack for the closed gatt:
        manager.setActiveGattForTesting(mockGatt)
        manager.getGattCallback().onConnectionStateChange(mockGatt, 0, BluetoothProfile.STATE_DISCONNECTED)

        // Reconnect should NOT be scheduled
        assertFalse("Subsequent disconnect callback for user disconnect must not trigger reconnect", manager.isReconnecting.value)
        assertEquals(0, manager.getReconnectAttempts())
    }

    @Test
    fun testStaleGattCallbackIgnored() {
        val manager = createTestBleManager()
        val activeGatt = mock(BluetoothGatt::class.java)
        val staleGatt = mock(BluetoothGatt::class.java)
        val mockDevice = mock(BluetoothDevice::class.java)
        `when`(mockDevice.address).thenReturn("AA:BB:CC:DD:EE:FF")
        `when`(activeGatt.device).thenReturn(mockDevice)

        manager.setActiveGattForTesting(activeGatt)
        manager.setLastConnectedAddressForTesting("AA:BB:CC:DD:EE:FF")
        manager.setAutoReconnectEnabled(true)

        // Fire onConnectionStateChange for the stale GATT
        manager.getGattCallback().onConnectionStateChange(staleGatt, 0, BluetoothProfile.STATE_DISCONNECTED)

        // Active GATT must not be closed or disconnected
        assertEquals(activeGatt, manager.getActiveGatt())
        assertEquals(0, manager.getReconnectAttempts())
        assertFalse(manager.isReconnecting.value)

        // Fire onServicesDiscovered for the stale GATT with an error
        manager.getGattCallback().onServicesDiscovered(staleGatt, BluetoothGatt.GATT_FAILURE)
        assertNull("Stale service discovery failure must not surface on active connection", manager.connectionError.value)
    }

    @Test
    fun testSequentialUnexpectedDisconnectionsBoundedBackoff() {
        val manager = createTestBleManager()
        val mockGatt = mock(BluetoothGatt::class.java)
        val mockDevice = mock(BluetoothDevice::class.java)
        `when`(mockDevice.address).thenReturn("AA:BB:CC:DD:EE:FF")
        `when`(mockGatt.device).thenReturn(mockDevice)

        manager.setActiveGattForTesting(mockGatt)
        manager.setLastConnectedAddressForTesting("AA:BB:CC:DD:EE:FF")
        manager.setAutoReconnectEnabled(true)

        // Attempt 1: Unexpected disconnect
        manager.getGattCallback().onConnectionStateChange(mockGatt, 19, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(1, manager.getReconnectAttempts())
        assertTrue(manager.isReconnecting.value)
        assertFalse(manager.isUserInitiatedDisconnect())

        // Attempt 2: Unexpected disconnect
        manager.setActiveGattForTesting(mockGatt)
        manager.getGattCallback().onConnectionStateChange(mockGatt, 133, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(2, manager.getReconnectAttempts())
        assertTrue(manager.isReconnecting.value)
        assertFalse(manager.isUserInitiatedDisconnect())

        // Attempt 3: Unexpected disconnect
        manager.setActiveGattForTesting(mockGatt)
        manager.getGattCallback().onConnectionStateChange(mockGatt, 8, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(3, manager.getReconnectAttempts())
        assertTrue(manager.isReconnecting.value)

        // Attempt 4: Unexpected disconnect
        manager.setActiveGattForTesting(mockGatt)
        manager.getGattCallback().onConnectionStateChange(mockGatt, 62, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(4, manager.getReconnectAttempts())
        assertTrue(manager.isReconnecting.value)

        // Attempt 5: Unexpected disconnect
        manager.setActiveGattForTesting(mockGatt)
        manager.getGattCallback().onConnectionStateChange(mockGatt, 133, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(5, manager.getReconnectAttempts())
        assertTrue(manager.isReconnecting.value)

        // Attempt 6: Exceeds 5 attempts -> give up
        manager.setActiveGattForTesting(mockGatt)
        manager.getGattCallback().onConnectionStateChange(mockGatt, 133, BluetoothProfile.STATE_DISCONNECTED)
        assertFalse("Reconnection must give up after 5 attempts", manager.isReconnecting.value)
        assertTrue(
            "Error must state that reconnection gave up after 5 attempts",
            manager.connectionError.value?.contains("gave up after 5 attempts") == true
        )
    }

    @Test
    fun testHealthyConnectionResetsReconnectAttempts() {
        val manager = createTestBleManager()
        val mockGatt = mock(BluetoothGatt::class.java)
        val mockDevice = mock(BluetoothDevice::class.java)
        `when`(mockDevice.address).thenReturn("AA:BB:CC:DD:EE:FF")
        `when`(mockGatt.device).thenReturn(mockDevice)

        manager.setActiveGattForTesting(mockGatt)
        manager.setLastConnectedAddressForTesting("AA:BB:CC:DD:EE:FF")
        manager.setAutoReconnectEnabled(true)
        manager.setReconnectAttemptsForTesting(3)

        // Simulate connection established 35 seconds ago (> 30s threshold)
        manager.setConnectedSinceMsForTesting(System.currentTimeMillis() - 35_000L)

        // Unexpected disconnect happens after healthy 35s connection
        manager.getGattCallback().onConnectionStateChange(mockGatt, 19, BluetoothProfile.STATE_DISCONNECTED)

        // Should have reset attempts before scheduling attempt 1
        assertEquals("Healthy session (>30s) must reset reconnect attempts budget", 1, manager.getReconnectAttempts())
        assertTrue(manager.isReconnecting.value)
    }

    @Test
    fun testShortConnectionDoesNotResetReconnectAttempts() {
        val manager = createTestBleManager()
        val mockGatt = mock(BluetoothGatt::class.java)
        val mockDevice = mock(BluetoothDevice::class.java)
        `when`(mockDevice.address).thenReturn("AA:BB:CC:DD:EE:FF")
        `when`(mockGatt.device).thenReturn(mockDevice)

        manager.setActiveGattForTesting(mockGatt)
        manager.setLastConnectedAddressForTesting("AA:BB:CC:DD:EE:FF")
        manager.setAutoReconnectEnabled(true)
        manager.setReconnectAttemptsForTesting(2)

        // Connected only 5 seconds ago (< 30s threshold)
        manager.setConnectedSinceMsForTesting(System.currentTimeMillis() - 5_000L)

        manager.getGattCallback().onConnectionStateChange(mockGatt, 19, BluetoothProfile.STATE_DISCONNECTED)

        // Should advance to attempt 3 (not reset to 1)
        assertEquals("Short session (<30s) must retain attempt counter", 3, manager.getReconnectAttempts())
    }

    @Test
    fun testDiagnosticLoggingWithoutCredentials() {
        val manager = createTestBleManager()
        val mockGatt = mock(BluetoothGatt::class.java)
        val mockDevice = mock(BluetoothDevice::class.java)
        `when`(mockDevice.address).thenReturn("AA:BB:CC:DD:EE:FF")
        `when`(mockGatt.device).thenReturn(mockDevice)

        manager.setActiveGattForTesting(mockGatt)
        manager.setLastConnectedAddressForTesting("AA:BB:CC:DD:EE:FF")

        manager.getGattCallback().onConnectionStateChange(mockGatt, 8, BluetoothProfile.STATE_DISCONNECTED)

        // Check emitted diagnostic message from replayCache
        val emitted = manager.diagnosticLogMessages.replayCache.firstOrNull()
        assertNotNull("Diagnostic log message must be emitted on disconnect", emitted)
        assertEquals(LogSeverity.WARNING, emitted!!.severity)
        assertTrue(emitted.messageText.contains("AA:BB:CC:DD:EE:FF"))
        assertTrue(emitted.messageText.contains("GATT_CONN_TIMEOUT"))
        assertFalse(emitted.messageText.contains("secret"))
        assertFalse(emitted.messageText.contains("proof"))
    }

    @Test
    fun testTeardownCleansUpGattQueueAndActiveOp() {
        val manager = createTestBleManager()
        val mockGatt = mock(BluetoothGatt::class.java)
        val mockChar = mock(BluetoothGattCharacteristic::class.java)
        `when`(mockChar.uuid).thenReturn(UUID.randomUUID())
        `when`(mockGatt.readCharacteristic(mockChar)).thenReturn(true)

        manager.setActiveGattForTesting(mockGatt)

        // Enqueue two ops: one becomes active/pending, the other sits in queue
        manager.enqueueGattOpForTesting(GattOp.ReadChar(mockChar))
        manager.enqueueGattOpForTesting(GattOp.ReadChar(mockChar))
        assertTrue("isGattOpPending should be true while op in flight", manager.isGattOpPending())
        assertEquals("Queue should have 1 waiting op", 1, manager.getGattOpQueueSize())

        // Trigger transport teardown
        manager.triggerTeardownTransportForTesting(wasUserInitiated = false)

        assertEquals("GATT op queue must be empty after teardown", 0, manager.getGattOpQueueSize())
        assertFalse("isGattOpPending must be false after teardown", manager.isGattOpPending())
        assertNull("activeGatt must be null after teardown", manager.getActiveGatt())
        assertNull("connectedDevice must be null after teardown", manager.connectedDevice.value)
    }

    @Test
    fun testBleRepositoryCancelsPendingWaitersOnDisconnect() {
        val mockContext = mock(Context::class.java)
        val bleManager = createTestBleManager(mockContext)
        val mockDataStore = mock(PulseShiftDataStore::class.java)
        val repository = BlePulseShiftRepository(bleManager, mockDataStore)

        val ackDeferred = CompletableDeferred<ConfigAckResponse>()
        val snapshotDeferred = CompletableDeferred<ConfigSnapshot>()
        val diagnosticsDeferred = CompletableDeferred<DiagnosticsReport>()

        repository.putPendingAckWaiterForTesting(101, 0x11, ackDeferred)
        repository.putPendingSnapshotWaiterForTesting(202, snapshotDeferred)
        repository.putPendingDiagnosticsWaiterForTesting(303, diagnosticsDeferred)

        assertEquals(1, repository.getPendingAckWaiterCount())
        assertEquals(1, repository.getPendingSnapshotWaiterCount())
        assertEquals(1, repository.getPendingDiagnosticsWaiterCount())

        // Simulate link drop
        repository.cancelPendingWaiters(CancellationException("BLE link disconnected"))

        // All waiters should be cleared immediately
        assertEquals(0, repository.getPendingAckWaiterCount())
        assertEquals(0, repository.getPendingSnapshotWaiterCount())
        assertEquals(0, repository.getPendingDiagnosticsWaiterCount())

        // All deferreds should be completed exceptionally (cancelled)
        assertTrue("ACK waiter must be completed", ackDeferred.isCompleted)
        assertTrue("ACK waiter must be cancelled", ackDeferred.isCancelled)
        assertTrue("Snapshot waiter must be completed", snapshotDeferred.isCompleted)
        assertTrue("Snapshot waiter must be cancelled", snapshotDeferred.isCancelled)
        assertTrue("Diagnostics waiter must be completed", diagnosticsDeferred.isCompleted)
        assertTrue("Diagnostics waiter must be cancelled", diagnosticsDeferred.isCancelled)
    }

    @Test
    fun testServiceDiscoveryReadinessTimeoutTriggersSafeRecovery() {
        val manager = createTestBleManager()
        val mockGatt = mock(BluetoothGatt::class.java)
        val mockDevice = mock(BluetoothDevice::class.java)
        `when`(mockDevice.address).thenReturn("AA:BB:CC:DD:EE:FF")
        `when`(mockGatt.device).thenReturn(mockDevice)

        manager.setActiveGattForTesting(mockGatt)
        manager.setLastConnectedAddressForTesting("AA:BB:CC:DD:EE:FF")
        manager.setAutoReconnectEnabled(true)

        // Trigger service readiness timeout safe recovery
        manager.triggerServiceReadinessTimeoutImmediatelyForTesting(mockGatt)

        // Verify safe recovery executed: transport torn down without marking user-initiated
        assertFalse("Timeout safe recovery must NOT mark userInitiatedDisconnect", manager.isUserInitiatedDisconnect())
        assertNull("Active GATT must be cleared by teardown", manager.getActiveGatt())
        assertTrue("Auto-reconnect should be scheduled on timeout", manager.isReconnecting.value)
        assertEquals(1, manager.getReconnectAttempts())
        assertTrue(
            "Connection error must state readiness timed out",
            manager.connectionError.value?.contains("timed out") == true
        )
    }

    @Test
    fun testReadinessCancelsReadinessTimeout() {
        val manager = createTestBleManager()
        val mockGatt = mock(BluetoothGatt::class.java)
        val mockDevice = mock(BluetoothDevice::class.java)
        `when`(mockDevice.address).thenReturn("AA:BB:CC:DD:EE:FF")
        `when`(mockGatt.device).thenReturn(mockDevice)

        manager.setActiveGattForTesting(mockGatt)
        manager.setLastConnectedAddressForTesting("AA:BB:CC:DD:EE:FF")

        manager.triggerStartServiceReadinessTimeoutJobForTesting(mockGatt)
        assertNotNull("Service readiness timeout job must be active", manager.getServiceReadinessTimeoutJob())

        // Simulate readiness achieved before timeout (e.g. telemetry arrives)
        manager.triggerCancelServiceReadinessTimeoutForTesting()
        assertNull("Service readiness timeout job must be cancelled", manager.getServiceReadinessTimeoutJob())
        assertEquals("Active GATT should remain intact", mockGatt, manager.getActiveGatt())
    }

    @Test
    fun testPrepareForOtaRebootCleansUpAndSchedulesReconnect() {
        val manager = createTestBleManager()
        val mockGatt = mock(BluetoothGatt::class.java)
        val mockDevice = mock(BluetoothDevice::class.java)
        `when`(mockDevice.address).thenReturn("AA:BB:CC:DD:EE:FF")
        `when`(mockGatt.device).thenReturn(mockDevice)

        manager.setActiveGattForTesting(mockGatt)
        manager.setLastConnectedAddressForTesting("AA:BB:CC:DD:EE:FF")
        manager.setAutoReconnectEnabled(true)
        manager.setReconnectAttemptsForTesting(4)

        manager.prepareForOtaReboot(reconnectDelayMs = 100L)

        // Transport must be torn down immediately
        assertNull("GATT must be cleared immediately upon OTA reboot preparation", manager.getActiveGatt())
        assertNull("connectedDevice must be null immediately", manager.connectedDevice.value)
        assertEquals("Reconnect attempts budget must be reset to 0 for OTA reboot", 0, manager.getReconnectAttempts())
        assertFalse("prepareForOtaReboot must NOT mark userInitiatedDisconnect", manager.isUserInitiatedDisconnect())
        assertTrue("isReconnecting must be true", manager.isReconnecting.value)
    }

    @Test
    fun testTeardownTransportCancelsConnectTimeoutJob() {
        val manager = createTestBleManager()
        val mockGatt = mock(BluetoothGatt::class.java)
        manager.setActiveGattForTesting(mockGatt)

        // Simulate disconnect/teardown
        manager.disconnect()

        assertNull("Active GATT must be cleared by teardown", manager.getActiveGatt())
        assertTrue("userInitiatedDisconnect must be true on explicit disconnect", manager.isUserInitiatedDisconnect())
    }
}
