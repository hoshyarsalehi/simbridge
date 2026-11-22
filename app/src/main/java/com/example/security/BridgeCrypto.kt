package com.example.security

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * رمزنگاری فرمان‌ها و پیام‌های بین دو گوشی (AES-256-GCM).
 * کلید از «کد امنیتی» مشترک + دو عدد تصادفی هر نشست ساخته می‌شود.
 */
object BridgeCrypto {
    private const val PBKDF2_ITERATIONS = 20000
    private const val KEY_BITS = 256
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun newNonce(): ByteArray {
        val nonce = ByteArray(16)
        random.nextBytes(nonce)
        return nonce
    }

    fun deriveKey(code: String, hostNonce: ByteArray, companionNonce: ByteArray): SecretKeySpec {
        val password = if (code.isEmpty()) "simbridge" else code
        val salt = hostNonce + companionNonce
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val bytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(key: SecretKeySpec, plain: String): String {
        val iv = ByteArray(IV_BYTES)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(key: SecretKeySpec, encoded: String): String? {
        return try {
            val all = Base64.decode(encoded, Base64.NO_WRAP)
            if (all.size <= IV_BYTES) {
                null
            } else {
                val iv = all.copyOfRange(0, IV_BYTES)
                val body = all.copyOfRange(IV_BYTES, all.size)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
                String(cipher.doFinal(body), Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }
}
