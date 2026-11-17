package com.pulseshift.app.data.ble

import com.pulseshift.app.domain.model.OTA_MAX_PACKAGE_BYTES
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.CRC32

internal class SignedOtaImage private constructor(
    val sha256: ByteArray,
    val runningDigest: ByteArray,
    val crc32: Long
) {
    companion object {
        private const val TRAILER_BYTES = 76

        // NIST P-256 SubjectPublicKeyInfo prefix followed by the firmware's SEC1 public key.
        private val trustedKey: PublicKey by lazy {
            val hex = "3059301306072a8648ce3d020106082a8648ce3d030107034200" +
                "040072cffa71b451afe19b5472fe56220653ceb6ae1b534459f7be14ddc6939336cb" +
                "0ea85068b48c51fda40f3af4725473085b51f1c4b1660e017fd1073ed1e13a"
            val encoded = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        }

        fun validate(bytes: ByteArray): SignedOtaImage = validate(bytes, trustedKey)

        internal fun validate(bytes: ByteArray, key: PublicKey): SignedOtaImage {
            require(bytes.size in 65_536..OTA_MAX_PACKAGE_BYTES) {
                "Firmware package must be between 64 KiB and 1.25 MiB."
            }
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes[0] == 0xE9.toByte() && header.getShort(12) == 0.toShort() &&
                header.getInt(32) == 0xABCD5432.toInt()) {
                "Select a signed ESP32 application image for the PulseShift DevKit V1."
            }
            val appSize = bytes.size - TRAILER_BYTES
            val trailer = ByteBuffer.wrap(bytes, appSize, TRAILER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            require(trailer.int == 0x544F5350 && trailer.get() == 1.toByte() &&
                trailer.get() == 0.toByte()) {
                "The firmware package is missing a PSOT version 1 signature trailer."
            }
            val crc32 = CRC32().apply { update(bytes, 0, appSize) }.value
            require((trailer.int.toLong() and 0xFFFFFFFFL) == crc32 && trailer.short == 64.toShort()) {
                "The firmware package CRC32 or signature length is invalid."
            }
            val raw = ByteArray(64).also { trailer.get(it) }
            val signatureValid = try {
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(key)
                    update(bytes, 0, appSize)
                    verify(rawP256ToDer(raw))
                }
            } catch (_: GeneralSecurityException) {
                false
            }
            require(signatureValid) { "The firmware signature does not match the trusted PulseShift key." }

            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(bytes, 0, appSize)
            val sha256 = digest.digest()
            val runningDigest = when (bytes[23].toInt()) {
                0 -> sha256
                1 -> {
                    val offset = appSize - 32
                    digest.update(bytes, 0, offset)
                    digest.digest().also {
                        require(it.contentEquals(bytes.copyOfRange(offset, appSize))) {
                            "The ESP32 application digest is invalid."
                        }
                    }
                }
                else -> throw IllegalArgumentException("The ESP32 application hash flag is invalid.")
            }
            return SignedOtaImage(sha256, runningDigest, crc32)
        }

        private fun rawP256ToDer(signature: ByteArray): ByteArray {
            fun integer(offset: Int): ByteArray {
                val scalar = BigInteger(1, signature.copyOfRange(offset, offset + 32)).toByteArray()
                return byteArrayOf(0x02, scalar.size.toByte()) + scalar
            }
            val scalars = integer(0) + integer(32)
            return byteArrayOf(0x30, scalars.size.toByte()) + scalars
        }
    }
}
