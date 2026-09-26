package com.example.dpop.auth_password.internal

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.util.UUID

/**
 * Password hashing; the plaintext password never gets persisted.
 *
 * Argon2id with the OWASP parameters (19 MiB memory, 2 iterations, 1 lane; review 2026-09 Phase F).
 * Should the parameters be raised later, [upgrade] rehashes an account on its next successful login,
 * so nobody has to reset a password. There is no older format to verify: no password was ever stored
 * any other way in data worth keeping (review 2026-09-26).
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
     *
     * Callers must not short-circuit this call away either: `enrollment != null && matches(...)`
     * re-opens exactly the same oracle one level up (see [AuthPasswordLookupToolHandler]).
     */
    fun matches(candidate: String, stored: String?): Boolean = when {
        stored != null && stored.startsWith(ARGON2_PREFIX) -> argon2.matches(candidate, stored)
        else -> {
            argon2.matches(candidate, DUMMY_HASH)
            false
        }
    }

    /** Whether [stored] is an Argon2id hash with weaker parameters than today's. */
    fun needsRehash(stored: String?): Boolean =
        stored != null && stored.startsWith(ARGON2_PREFIX) && argon2.upgradeEncoding(stored)

    /**
     * After a SUCCESSFUL check only: moves [enrollment] to today's hash. Runs inside the caller's
     * transaction - the entity is managed, so the new hash is written with it.
     */
    fun upgrade(enrollment: AuthPasswordEnrollment, verifiedPassword: String) {
        if (needsRehash(enrollment.passwordHash)) enrollment.passwordHash = hash(verifiedPassword)
    }

    private const val ARGON2_PREFIX = "\$argon2"
}
