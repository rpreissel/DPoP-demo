package com.example.dpop.auth_password.internal

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted PBKDF2 password hashing; the plaintext password never gets persisted. */
internal object PasswordHasher {
    private const val ITERATIONS = 210_000
    private const val KEY_LENGTH = 256
    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val hash = pbkdf2(password, salt)
        return "$ITERATIONS:${encode(salt)}:${encode(hash)}"
    }

    fun matches(candidate: String, stored: String?): Boolean {
        if (stored == null) return false
        val parts = stored.split(":")
        if (parts.size != 3) return false
        val iterations = parts[0].toIntOrNull() ?: return false
        val salt = decode(parts[1])
        val expected = decode(parts[2])
        val actual = pbkdf2(candidate, salt, iterations)
        return actual.contentEquals(expected)
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
}
