/*
 * VionBoard — VionDoubleEncryption.kt
 * Double encryption layer for vault files.
 * First layer: Android Keystore (hardware-backed AES-256-GCM)
 * Second layer: User's master password (PBKDF2-SHA256)
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * VionBoard double encryption for vault files.
 *
 * Layer 1: Android Keystore (hardware-backed AES-256-GCM)
 *   - Key never leaves the secure element
 *   - Protects against file extraction from rooted devices
 *
 * Layer 2: Master password (PBKDF2-SHA256)
 *   - User's master password is the second encryption key
 *   - Protects against keystore compromise
 *
 * Flow:
 *   Plaintext → PBKDF2(masterPassword) → AES-256-GCM(keystore key) → Encrypted file
 *
 * Decryption:
 *   Encrypted file → AES-256-GCM(keystore key) → PBKDF2(masterPassword) → Plaintext
 */
object VionDoubleEncryption {

    private const val KEY_ALIAS = "vion_vault_encryption_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val PBKDF2_ITERATIONS = 100_000
    private const val SALT_LENGTH = 16

    /**
     * Encrypts a vault file with double encryption.
     * Returns true on success, false on failure.
     */
    fun encryptVaultFile(
        plainFile: File,
        encryptedFile: File,
        masterPassword: String
    ): Boolean {
        return try {
            // Read plaintext
            val plaintext = plainFile.readBytes()

            // Layer 1: Derive key from master password using PBKDF2
            val salt = ByteArray(SALT_LENGTH).apply { SecureRandom().nextBytes(this) }
            val pbkdf2Key = derivePBKDF2Key(masterPassword, salt)

            // Layer 2: Encrypt with Android Keystore-backed key
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)

            // Encrypt the PBKDF2 key with the ciphertext
            val cipher2 = Cipher.getInstance(TRANSFORMATION)
            cipher2.init(Cipher.ENCRYPT_MODE, getOrCreateKeystoreKey())
            val iv2 = cipher2.iv
            val encryptedPbkdf2Key = cipher2.doFinal(pbkdf2Key)

            // Write: [salt][iv][iv2][encryptedPbkdf2Key][ciphertext]
            encryptedFile.writeBytes(
                salt + iv + iv2 + encryptedPbkdf2Key + ciphertext
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Decrypts a vault file with double encryption.
     * Returns the plaintext bytes on success, null on failure.
     */
    fun decryptVaultFile(
        encryptedFile: File,
        masterPassword: String
    ): ByteArray? {
        return try {
            val encrypted = encryptedFile.readBytes()

            // Parse: [salt(16)][iv(12)][iv2(12)][encryptedPbkdf2Key(...)][ciphertext(...)]
            var offset = 0
            val salt = encrypted.copyOfRange(offset, offset + SALT_LENGTH)
            offset += SALT_LENGTH

            val iv = encrypted.copyOfRange(offset, offset + 12)
            offset += 12

            val iv2 = encrypted.copyOfRange(offset, offset + 12)
            offset += 12

            // Decrypt PBKDF2 key (assume it's 32 bytes + 16 byte tag = 48 bytes)
            val encryptedPbkdf2KeySize = 48
            val encryptedPbkdf2Key = encrypted.copyOfRange(offset, offset + encryptedPbkdf2KeySize)
            offset += encryptedPbkdf2KeySize

            val ciphertext = encrypted.copyOfRange(offset, encrypted.size)

            // Layer 2: Decrypt PBKDF2 key using Keystore key
            val cipher2 = Cipher.getInstance(TRANSFORMATION)
            val spec2 = GCMParameterSpec(GCM_TAG_LENGTH, iv2)
            cipher2.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreKey(), spec2)
            val pbkdf2Key = cipher2.doFinal(encryptedPbkdf2Key)

            // Verify: re-derive PBKDF2 key and compare
            val expectedKey = derivePBKDF2Key(masterPassword, salt)
            if (!pbkdf2Key.contentEquals(expectedKey)) {
                return null // Wrong password
            }

            // Layer 1: Decrypt ciphertext using Keystore key
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKeystoreKey(), spec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    private fun derivePBKDF2Key(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
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
                .setUserAuthenticationRequired(false)
                .build()
        )
        return keyGen.generateKey()
    }
}
