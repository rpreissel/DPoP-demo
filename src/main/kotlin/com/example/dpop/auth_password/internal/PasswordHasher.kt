package com.example.dpop.auth_password.internal

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted PBKDF2 password hashing; the plaintext password never gets persisted. */
internal object PasswordHasher {
    private const val ITERATIONS = 210_000
    private const val KEY_LENGTH = 256
    private val random = SecureRandom()

    /**
     * A real hash of a value nobody knows, generated once per boot. Verifying against it costs
     * exactly what verifying against a genuine credential costs, and can never match.
     */
    private val DUMMY_HASH: String = hash(UUID.randomUUID().toString())

    fun hash(password: String): String {
        val salt = ByteArray(16).also { random.nextBytes(it) }
        return "$ITERATIONS:${encode(salt)}:${encode(pbkdf2(password, salt))}"
    }

    /**
     * Always spends the full PBKDF2 work, whatever [stored] is.
     *
     * Returning early for a missing or malformed hash would make "this e-mail has no password
     * credential" a sub-millisecond answer while a real account costs 210k iterations - a
     * reliable account-enumeration oracle that no care in the caller's response shape can hide.
     * Every non-verifiable case therefore falls through to [DUMMY_HASH] instead of returning.
     *
     * Callers must not short-circuit this call away either: `enrollment != null && matches(...)`
     * re-opens exactly the same oracle one level up (see [AuthPasswordLookupToolHandler]).
     */
    fun matches(candidate: String, stored: String?): Boolean =
        verify(candidate, parse(stored) ?: parse(DUMMY_HASH)!!)

    private data class Parsed(val iterations: Int, val salt: ByteArray, val expected: ByteArray)

    private fun parse(stored: String?): Parsed? {
        val parts = stored?.split(":") ?: return null
        if (parts.size != 3) return null
        val iterations = parts[0].toIntOrNull() ?: return null
        return try {
            Parsed(iterations, decode(parts[1]), decode(parts[2]))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun verify(candidate: String, parsed: Parsed): Boolean {
        val actual = pbkdf2(candidate, parsed.salt, parsed.iterations)
        // Constant-time: a short-circuiting compare leaks how many leading bytes matched.
        return MessageDigest.isEqual(actual, parsed.expected)
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
}
