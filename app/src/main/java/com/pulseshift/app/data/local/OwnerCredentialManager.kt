package com.pulseshift.app.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

open class OwnerCredentialManager(private val context: Context) {
    private val keyAlias = "PulseShiftOwnerSecretKey"
    private val prefsFilename = "pulseshift_owner_credentials"
    private val prefs by lazy { context.getSharedPreferences(prefsFilename, Context.MODE_PRIVATE) }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existingKey = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existingKey != null) return existingKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    open fun generateNewSecret(): ByteArray {
        val secret = ByteArray(12)
        SecureRandom().nextBytes(secret)
        return secret
    }

    open fun hasEnrolledSecret(deviceAddress: String): Boolean {
        val key = "secret_$deviceAddress"
        return prefs.contains(key)
    }

    open fun getEnrolledSecret(deviceAddress: String): ByteArray? {
        val key = "secret_$deviceAddress"
        val encryptedDataStr = prefs.getString(key, null) ?: return null
        val ivStr = prefs.getString("${key}_iv", null) ?: return null

        return try {
            val encryptedData = Base64.decode(encryptedDataStr, Base64.DEFAULT)
            val iv = Base64.decode(ivStr, Base64.DEFAULT)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)

            cipher.doFinal(encryptedData)
        } catch (e: Exception) {
            // Fail closed on Keystore error or corruption
            null
        }
    }

    open fun saveEnrolledSecret(deviceAddress: String, secret: ByteArray): Boolean {
        require(secret.size == 12) { "Owner secret must be exactly 12 bytes" }
        val key = "secret_$deviceAddress"
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val encryptedData = cipher.doFinal(secret)
            val iv = cipher.iv

            prefs.edit()
                .putString(key, Base64.encodeToString(encryptedData, Base64.DEFAULT))
                .putString("${key}_iv", Base64.encodeToString(iv, Base64.DEFAULT))
                .commit()
        } catch (e: Exception) {
            // Fail closed - NEVER store plaintext or unencrypted credentials
            false
        }
    }

    open fun clearEnrolledSecret(deviceAddress: String) {
        val key = "secret_$deviceAddress"
        prefs.edit()
            .remove(key)
            .remove("${key}_iv")
            .apply()
    }
}
