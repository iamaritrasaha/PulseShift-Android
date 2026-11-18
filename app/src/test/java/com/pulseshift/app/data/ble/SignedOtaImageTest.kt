package com.pulseshift.app.data.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.zip.CRC32

class SignedOtaImageTest {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun packageFixture(): ByteArray {
        val app = ByteArray(70_000)
        ByteBuffer.wrap(app).order(ByteOrder.LITTLE_ENDIAN)
            .put(0, 0xE9.toByte()).put(23, 1).putInt(32, 0xABCD5432.toInt())
        MessageDigest.getInstance("SHA-256").digest(app.copyOf(app.size - 32))
            .copyInto(app, app.size - 32)
        val raw = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(keyPair.private)
            update(app)
            sign()
        }
        val crc = CRC32().apply { update(app) }.value
        return app + ByteBuffer.allocate(76).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x544F5350).put(1).put(0).putInt(crc.toInt()).putShort(64).put(raw).array()
    }

    @Test
    fun validPackageUsesDifferentTransferAndRunningDigests() {
        val bytes = packageFixture()
        val image = SignedOtaImage.validate(bytes, keyPair.public)
        assertArrayEquals(bytes.copyOfRange(69_968, 70_000), image.runningDigest)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes.copyOf(70_000)), image.sha256)
    }

    @Test
    fun productionPinRejectsOtherSigningKeys() {
        val error = assertThrows(IllegalArgumentException::class.java) { SignedOtaImage.validate(packageFixture()) }
        assertEquals("The firmware signature does not match the trusted PulseShift key.", error.message)
    }

    @Test
    fun rejectsCorruptionAndSignatureTamperingIndependently() {
        val corrupt = packageFixture()
        corrupt[60] = 2
        val crcError = assertThrows(IllegalArgumentException::class.java) {
            SignedOtaImage.validate(corrupt, keyPair.public)
        }
        assertEquals("The firmware package CRC32 or signature length is invalid.", crcError.message)
        val forgedSignature = packageFixture()
        forgedSignature[forgedSignature.lastIndex] = (forgedSignature.last().toInt() xor 1).toByte()
        val signatureError = assertThrows(IllegalArgumentException::class.java) {
            SignedOtaImage.validate(forgedSignature, keyPair.public)
        }
        assertEquals("The firmware signature does not match the trusted PulseShift key.", signatureError.message)
    }

    @Test
    fun rejectsTruncationAndWrongChip() {
        assertThrows(IllegalArgumentException::class.java) {
            SignedOtaImage.validate(ByteArray(65_535), keyPair.public)
        }
        val wrongChip = packageFixture()
        wrongChip[12] = 5
        assertThrows(IllegalArgumentException::class.java) { SignedOtaImage.validate(wrongChip, keyPair.public) }
    }
}
