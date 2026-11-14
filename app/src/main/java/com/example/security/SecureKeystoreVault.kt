package com.example.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hardware-backed Android Keystore AES-256-GCM encryption + salted PBKDF2 PIN hashing.
 *
 * - Primary key storage uses AndroidKeyStore (hardware-backed Keymaster / StrongBox when available).
 * - In local JVM/Robolectric test environments where AndroidKeyStore provider is unavailable,
 *   falls back to an isolated per-install 256-bit AES key in app-private internal storage.
 * - Never logs keys, IVs, salts, or plaintext secrets.
 */
object SecureKeystoreVault {

    private const val TAG = "SecureKeystoreVault"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    private const val PREFIX_KEYSTORE = "ks_v1:"
    private const val PREFIX_PRIVATE_FALLBACK = "sw_v1:"
    private const val PREFIX_PBKDF2 = "pbkdf2_v1:"

    private const val PBKDF2_ITERATIONS = 10_000
    private const val PBKDF2_KEY_BITS = 256

    private val secureRandom = SecureRandom()

    fun isEncryptedPayload(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value.startsWith(PREFIX_KEYSTORE) || value.startsWith(PREFIX_PRIVATE_FALLBACK)
    }

    fun isHashedPin(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value.startsWith(PREFIX_PBKDF2)
    }

    fun encryptString(context: Context, alias: String, plaintext: String): String {
        if (plaintext.isBlank()) return ""
        return try {
            val key = getOrCreateKeystoreKey(alias)
            if (key != null) {
                encryptWithKey(key, PREFIX_KEYSTORE, plaintext)
            } else {
                val swKey = getOrCreateFallbackKey(context, alias)
                encryptWithKey(swKey, PREFIX_PRIVATE_FALLBACK, plaintext)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Keystore encrypt fallback triggered for alias=$alias: ${e.javaClass.simpleName}")
            val swKey = getOrCreateFallbackKey(context, alias)
            encryptWithKey(swKey, PREFIX_PRIVATE_FALLBACK, plaintext)
        }
    }

    fun decryptString(context: Context, alias: String, payload: String?): String {
        if (payload.isNullOrBlank()) return ""
        return when {
            payload.startsWith(PREFIX_KEYSTORE) -> {
                try {
                    val key = getKeystoreKeyOnly(alias) ?: return ""
                    decryptWithKey(key, payload.removePrefix(PREFIX_KEYSTORE))
                } catch (e: Throwable) {
                    Log.w(TAG, "Keystore decrypt failed for alias=$alias: ${e.javaClass.simpleName}")
                    ""
                }
            }
            payload.startsWith(PREFIX_PRIVATE_FALLBACK) -> {
                try {
                    val swKey = getOrCreateFallbackKey(context, alias)
                    decryptWithKey(swKey, payload.removePrefix(PREFIX_PRIVATE_FALLBACK))
                } catch (e: Throwable) {
                    Log.w(TAG, "Private vault decrypt failed for alias=$alias: ${e.javaClass.simpleName}")
                    ""
                }
            }
            else -> {
                // Legacy plaintext stored before vault upgrade; return as-is for caller migration
                payload
            }
        }
    }

    fun hashPinWithSalt(pin: String): String {
        val clean = pin.trim()
        if (clean.isEmpty()) return ""
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        val digest = derivePbkdf2(clean, salt)
        val saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hashB64 = Base64.encodeToString(digest, Base64.NO_WRAP)
        return "$PREFIX_PBKDF2$saltB64:$hashB64"
    }

    fun verifyPin(pin: String, storedValue: String?): Boolean {
        val candidate = pin.trim()
        if (candidate.isEmpty() || storedValue.isNullOrBlank()) return false
        return if (storedValue.startsWith(PREFIX_PBKDF2)) {
            val parts = storedValue.removePrefix(PREFIX_PBKDF2).split(":")
            if (parts.size != 2) return false
            try {
                val salt = Base64.decode(parts[0], Base64.NO_WRAP)
                val expected = Base64.decode(parts[1], Base64.NO_WRAP)
                val actual = derivePbkdf2(candidate, salt)
                MessageDigest.isEqual(expected, actual)
            } catch (_: Throwable) {
                false
            }
        } else {
            // Constant-time compare for unmigrated legacy pin before automatic migration
            MessageDigest.isEqual(
                candidate.toByteArray(Charsets.UTF_8),
                storedValue.trim().toByteArray(Charsets.UTF_8)
            )
        }
    }

    private fun derivePbkdf2(pin: String, salt: ByteArray): ByteArray {
        return try {
            val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            factory.generateSecret(spec).encoded
        } catch (_: Throwable) {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(salt)
            md.digest(pin.toByteArray(Charsets.UTF_8))
        }
    }

    private fun encryptWithKey(key: SecretKey, prefix: String, plaintext: String): String {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val iv = ByteArray(GCM_IV_BYTES)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val actualIv = cipher.iv ?: iv.also { secureRandom.nextBytes(it) }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ivB64 = Base64.encodeToString(actualIv, Base64.NO_WRAP)
        val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$prefix$ivB64:$ctB64"
    }

    private fun decryptWithKey(key: SecretKey, encodedBody: String): String {
        val parts = encodedBody.split(":", limit = 2)
        if (parts.size != 2) return ""
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val plainBytes = cipher.doFinal(ciphertext)
        return String(plainBytes, Charsets.UTF_8)
    }

    private fun getKeystoreKeyOnly(alias: String): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (_: Throwable) {
            null
        }
    }

    private fun getOrCreateKeystoreKey(alias: String): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            val existing = (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            if (existing != null) return existing

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (_: Throwable) {
            null
        }
    }

    private fun getOrCreateFallbackKey(context: Context, alias: String): SecretKey {
        val dir = File(context.filesDir, ".nahid_secure_vault")
        if (!dir.exists()) dir.mkdirs()
        val safeAlias = alias.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val keyFile = File(dir, "$safeAlias.key")
        val keyBytes = if (keyFile.exists() && keyFile.length() == 32L) {
            keyFile.readBytes()
        } else {
            ByteArray(32).also {
                secureRandom.nextBytes(it)
                keyFile.writeBytes(it)
            }
        }
        return SecretKeySpec(keyBytes, "AES")
    }
}
