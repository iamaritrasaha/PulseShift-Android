package com.pulseshift.app.data.ble

import com.pulseshift.app.domain.model.AnimationSettings
import com.pulseshift.app.domain.model.ConfigAckResponse
import com.pulseshift.app.domain.model.ConfigSnapshot
import com.pulseshift.app.domain.model.LightingSetup
import com.pulseshift.app.domain.model.RpmMapping
import com.pulseshift.app.domain.model.RpmMappingMode
import com.pulseshift.app.domain.model.StartupAnimation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

internal object LightingSetupCodec {
    const val VERSION: Byte = 1
    const val HEADER = 15
    const val MAX = HEADER + 64 * 3
    const val CHUNK = 10

    fun encode(setup: LightingSetup): ByteArray {
        require(setup.valid)
        val mapping = setup.mapping
        val width = if (mapping.mode == RpmMappingMode.GROUPED) 3 else 2
        return buffer(HEADER + mapping.thresholds.size * width).apply {
            put(VERSION)
            put(setup.installedLedCount.toByte())
            put(mapping.activeLedCount.toByte())
            put(mapping.mode.ordinal.toByte())
            put(mapping.thresholds.size.toByte())
            putAnimation(setup.boot)
            putAnimation(setup.ambient)
            putShort(setup.warningRpm.toShort())
            mapping.thresholds.forEachIndexed { index, rpm ->
                putShort(rpm.toShort())
                if (width == 3) put(mapping.groupSizes[index].toByte())
            }
        }.array()
    }

    fun decode(bytes: ByteArray): LightingSetup? {
        if (bytes.size !in HEADER..MAX) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (input.get() != VERSION) return null
        val installed = input.u8()
        val active = input.u8()
        val mode = RpmMappingMode.entries.getOrNull(input.u8()) ?: return null
        val count = input.u8()
        val width = if (mode == RpmMappingMode.GROUPED) 3 else 2
        if (count > 64 || bytes.size != HEADER + count * width) return null
        val boot = input.animation() ?: return null
        val ambient = input.animation() ?: return null
        val warning = input.u16()
        val thresholds = ArrayList<Int>(count)
        val groupSizes = ArrayList<Int>(count)
        repeat(count) {
            thresholds.add(input.u16())
            if (width == 3) groupSizes.add(input.u8())
        }
        return LightingSetup(installed, RpmMapping(mode, active, thresholds, groupSizes), boot, ambient, warning)
            .takeIf { it.valid }
    }

    fun begin(bytes: ByteArray, revision: Long): ByteArray = buffer(11).apply {
        put(VERSION)
        putShort(bytes.size.toShort())
        putInt(CRC32().apply { update(bytes) }.value.toInt())
        putInt(revision.toInt())
    }.array()

    fun chunk(id: Int, offset: Int, data: ByteArray): ByteArray = buffer(4 + data.size).apply {
        require(data.size in 1..CHUNK)
        putShort(id.toShort())
        putShort(offset.toShort())
        put(data)
    }.array()

    fun transactionId(id: Int): ByteArray = buffer(2).putShort(id.toShort()).array()

    fun preview(animation: AnimationSettings): ByteArray = buffer(4).apply {
        require(animation.valid)
        put(VERSION)
        put(animation.pattern.id.toByte())
        putShort(animation.durationMs.toShort())
    }.array()

    fun verifiesSave(
        ack: ConfigAckResponse,
        readback: ConfigSnapshot?,
        expected: LightingSetup,
        expectedRevision: Long
    ): Boolean = ack.isSuccess && ack.isApplied && ack.isPersisted && !ack.isStagedOnly &&
        !ack.isAuthRequired && ack.activeRevision == expectedRevision && readback?.isValid == true &&
        readback.activeRevision == expectedRevision && readback.matchesFlash == true &&
        readback.savedInstalledLedCount == expected.installedLedCount && readback.lightingSetup == expected

    private fun buffer(length: Int) = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
    private fun ByteBuffer.u8() = get().toInt() and 0xff
    private fun ByteBuffer.u16() = short.toInt() and 0xffff

    private fun ByteBuffer.putAnimation(settings: AnimationSettings) {
        put(if (settings.enabled) 1.toByte() else 0.toByte())
        put(settings.pattern.id.toByte())
        putShort(settings.durationMs.toShort())
    }

    private fun ByteBuffer.animation(): AnimationSettings? {
        val enabled = u8()
        val id = u8()
        val duration = u16()
        val pattern = StartupAnimation.entries.firstOrNull { it.id == id } ?: return null
        return AnimationSettings(enabled == 1, pattern, duration).takeIf { enabled in 0..1 && it.valid }
    }
}
