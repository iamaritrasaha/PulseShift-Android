package com.pulseshift.app.domain.model

data class HealthFlags(
    val isLittleFsMounted: Boolean,
    val isStorageFull: Boolean,
    val isReceivePerformanceDegraded: Boolean,
    val isBleClientConnected: Boolean
) {
    companion object {
        fun fromByte(flags: Byte): HealthFlags {
            val b = flags.toInt() and 0xFF
            return HealthFlags(
                isLittleFsMounted = (b and 0x01) != 0,
                isStorageFull = (b and 0x02) != 0,
                isReceivePerformanceDegraded = (b and 0x04) != 0,
                isBleClientConnected = (b and 0x08) != 0
            )
        }
    }
}

data class CanHealthState(
    val version: Int,
    val healthFlags: HealthFlags,
    val sequence: Int,
    val driverMissedFrames: Long,
    val hardwareFifoOverruns: Long,
    val canBusErrors: Long,
    val recordedFramesInCurrentSession: Long,
    val timestampReceivedMs: Long = System.currentTimeMillis()
) {
    val hasLossOrErrors: Boolean
        get() = driverMissedFrames > 0 || hardwareFifoOverruns > 0 || canBusErrors > 0

    val storageStatusText: String
        get() = when {
            !healthFlags.isLittleFsMounted -> "Storage Unavailable"
            healthFlags.isStorageFull -> "Storage Full"
            else -> "LittleFS Mounted • Normal"
        }
}
