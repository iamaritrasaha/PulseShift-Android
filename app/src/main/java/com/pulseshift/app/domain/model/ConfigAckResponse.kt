package com.pulseshift.app.domain.model

data class ConfigAckResponse(
    val version: Int = 2,
    val opcode: Byte,
    val sequenceRef: Int,
    val statusCode: Byte,
    val resultFlags: Byte,
    val activeRevision: Long,
    val payload: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ConfigAckResponse
        if (version != other.version) return false
        if (opcode != other.opcode) return false
        if (sequenceRef != other.sequenceRef) return false
        if (statusCode != other.statusCode) return false
        if (resultFlags != other.resultFlags) return false
        if (activeRevision != other.activeRevision) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + opcode
        result = 31 * result + sequenceRef
        result = 31 * result + statusCode
        result = 31 * result + resultFlags
        result = 31 * result + activeRevision.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    val isSuccess: Boolean get() = statusCode.toInt() == STATUS_OK

    val isPersisted: Boolean get() = (resultFlags.toInt() and 0x01) != 0
    val isStagedOnly: Boolean get() = (resultFlags.toInt() and 0x02) != 0
    val isApplied: Boolean get() = (resultFlags.toInt() and 0x04) != 0
    val isAuthRequired: Boolean get() = (resultFlags.toInt() and 0x08) != 0

    val errorMessage: String
        get() = when (statusCode.toInt() and 0xFF) {
            STATUS_OK -> "Success"
            STATUS_ERR_INVALID_PARAM -> "Invalid parameter value"
            STATUS_ERR_UNSUPPORTED -> "Operation unsupported by controller"
            STATUS_ERR_BUSY -> "Controller busy — retry shortly"
            STATUS_ERR_UNAUTHORIZED -> "Unauthorized operation — pairing/authentication required"
            STATUS_ERR_INVALID_STATE -> "Invalid controller state machine transition"
            STATUS_ERR_STORAGE_ERROR -> "ESP32 flash storage error"
            STATUS_ERR_VERSION_MISMATCH -> "Protocol version mismatch"
            STATUS_ERR_VALIDATION_FAILED -> "Configuration validation failed"
            else -> "Unknown error (code ${statusCode.toInt() and 0xFF})"
        }

    companion object {
        const val STATUS_OK: Int = 0
        const val STATUS_ERR_INVALID_PARAM: Int = 1
        const val STATUS_ERR_UNSUPPORTED: Int = 2
        const val STATUS_ERR_BUSY: Int = 3
        const val STATUS_ERR_UNAUTHORIZED: Int = 4
        const val STATUS_ERR_INVALID_STATE: Int = 5
        const val STATUS_ERR_STORAGE_ERROR: Int = 6
        const val STATUS_ERR_VERSION_MISMATCH: Int = 7
        const val STATUS_ERR_VALIDATION_FAILED: Int = 8
    }
}
