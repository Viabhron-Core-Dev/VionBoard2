/*
 * VionBoard — VionKeystore.kt
 * Android Keystore backed AES-GCM encryption for protected entries.
 * No passwords leave the device. No internet. Fully local.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Thin wrapper around Android Keystore for AES-256-GCM encryption.
 * Used by ProtectedEntriesDao to store protected suggestion text.
 *
 * Key is generated once on first use and lives in the hardware-backed
 * Keystore — it never leaves the secure element.
 */
object VionKeystore {

    private const val KEY_ALIAS = "vion_protected_entries_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    /** Encrypts plaintext. Returns a ByteArray with IV prepended: [12-byte IV][ciphertext]. */
    fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv                          // 12 bytes for GCM
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // Prepend IV so we can recover it on decrypt
        return iv + ciphertext
    }

    /** Decrypts a blob produced by [encrypt]. Returns null on any failure (key missing, tampered). */
    fun decrypt(blob: ByteArray): String? {
        return try {
            if (blob.size < 13) return null         // must have at least IV + 1 byte
            val iv = blob.copyOfRange(0, 12)
            val ciphertext = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        // Generate a new hardware-backed AES-256 key
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false) // key always accessible; auth is in the UI layer
                .build()
        )
        return keyGen.generateKey()
    }
}
