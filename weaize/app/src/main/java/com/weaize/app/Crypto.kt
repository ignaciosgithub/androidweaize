package com.weaize.app

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for location payloads. The AES-256 key is derived from the user's private
 * key with PBKDF2-HMAC-SHA256; the server only ever stores opaque ciphertext, so only holders of
 * the private key can read locations.
 *
 * Wire format (base64): salt(16) || iv(12) || ciphertext+tag
 */
object Crypto {
  private const val PBKDF2_ITERATIONS = 100_000
  private const val SALT_LEN = 16
  private const val IV_LEN = 12

  private fun deriveKey(privateKey: String, salt: ByteArray): SecretKeySpec {
    val spec = PBEKeySpec(privateKey.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
    val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
    return SecretKeySpec(key.encoded, "AES")
  }

  fun encrypt(privateKey: String, plaintext: String): String {
    val rnd = SecureRandom()
    val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
    val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, deriveKey(privateKey, salt), GCMParameterSpec(128, iv))
    val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    return Base64.encodeToString(salt + iv + ct, Base64.NO_WRAP)
  }

  fun decrypt(privateKey: String, encoded: String): String {
    val raw = Base64.decode(encoded, Base64.NO_WRAP)
    val salt = raw.copyOfRange(0, SALT_LEN)
    val iv = raw.copyOfRange(SALT_LEN, SALT_LEN + IV_LEN)
    val ct = raw.copyOfRange(SALT_LEN + IV_LEN, raw.size)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, deriveKey(privateKey, salt), GCMParameterSpec(128, iv))
    return String(cipher.doFinal(ct), Charsets.UTF_8)
  }
}
