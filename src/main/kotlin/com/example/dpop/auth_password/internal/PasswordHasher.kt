package com.example.dpop.auth_password.internal

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Password hashing; the plaintext password never gets persisted.
 *
 * Argon2id with the OWASP parameters (19 MiB memory, 2 iterations, 1 lane; review 2026-09 Phase F).
 * Hashes from before - salted PBKDF2-SHA256, 210 000 iterations, `iterations:salt:hash` - still
 * verify, and [upgrade] replaces them with an Argon2id hash on the next successful login: nobody has
 * to reset a password, and every account moves over the first time it is used.
 */
internal object PasswordHasher {
    private val argon2 = Argon2PasswordEncoder(16, 32, 1, 19_456, 2)

    /**
     * A real hash of a value nobody knows, generated once per boot. Verifying against it costs
     * exactly what verifying against a genuine (Argon2id) credential costs, and can never match.
     */
    private val DUMMY_HASH: String = hash(UUID.randomUUID().toString())

    fun hash(password: String): String = checkNotNull(argon2.encode(password))

    /**
     * Always spends the full hashing work, whatever [stored] is.
     *
     * Returning early for a missing or malformed hash would make "this e-mail has no password
     * credential" a sub-millisecond answer while a real account costs a full Argon2id run - a
     * reliable account-enumeration oracle that no care in the caller's response shape can hide.
     * Every non-verifiable case therefore falls through to [DUMMY_HASH] instead of returning.
     * (A not-yet-upgraded PBKDF2 account costs differently until its first login; that window closes
     * by itself.)
     *
     * Callers must not short-circuit this call away either: `enrollment != null && matches(...)`
     * re-opens exactly the same oracle one level up (see [AuthPasswordLookupToolHandler]).
     */
    fun matches(candidate: String, stored: String?): Boolean = when {
        stored != null && stored.startsWith(ARGON2_PREFIX) -> argon2.matches(candidate, stored)
        stored != null && Legacy.parse(stored) != null -> Legacy.verify(candidate, Legacy.parse(stored)!!)
        else -> {
            argon2.matches(candidate, DUMMY_HASH)
            false
        }
    }

    /** Whether [stored] is weaker than today's hash - an old PBKDF2 hash, or Argon2id with lower parameters. */
    fun needsRehash(stored: String?): Boolean =
        stored != null && (!stored.startsWith(ARGON2_PREFIX) || argon2.upgradeEncoding(stored))

    /**
     * After a SUCCESSFUL check only: moves [enrollment] to today's hash. Runs inside the caller's
     * transaction - the entity is managed, so the new hash is written with it.
     */
    fun upgrade(enrollment: AuthPasswordEnrollment, verifiedPassword: String) {
        if (needsRehash(enrollment.passwordHash)) enrollment.passwordHash = hash(verifiedPassword)
    }

    private const val ARGON2_PREFIX = "\$argon2"

    /** The PBKDF2 hashes written before Argon2id - only ever verified, never written again. */
    private object Legacy {
        private const val KEY_LENGTH = 256

        data class Parsed(val iterations: Int, val salt: ByteArray, val expected: ByteArray)

        fun parse(stored: String): Parsed? {
            val parts = stored.split(":")
            if (parts.size != 3) return null
            val iterations = parts[0].toIntOrNull() ?: return null
            return try {
                Parsed(iterations, Base64.getDecoder().decode(parts[1]), Base64.getDecoder().decode(parts[2]))
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        fun verify(candidate: String, parsed: Parsed): Boolean {
            val spec = PBEKeySpec(candidate.toCharArray(), parsed.salt, parsed.iterations, KEY_LENGTH)
            val actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            // Constant-time: a short-circuiting compare leaks how many leading bytes matched.
            return MessageDigest.isEqual(actual, parsed.expected)
        }
    }
}
