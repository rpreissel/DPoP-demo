package com.example.dpop.auth_email.internal

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/**
 * Generates and hashes attempt-scoped confirmation codes; the plaintext never gets persisted.
 * A deliberate near-duplicate of auth_sms's TanGenerator rather than a shared dependency - leaf
 * modules stay decoupled from each other (docs/08-projektrahmen.md #3), and `internal` in Kotlin
 * is only enforced per compilation module, not per package, so reusing it across auth_sms/
 * auth_email would silently violate the Modulith boundary the two modules are meant to have.
 */
internal object EmailCodeGenerator {
    private val random = SecureRandom()
    val validity: Duration = Duration.ofMinutes(5)

    data class Issued(val plainCode: String, val hash: String, val expiresAt: Instant)

    fun issue(): Issued {
        val code = (random.nextInt(900_000) + 100_000).toString()
        return Issued(code, hash(code), Instant.now().plus(validity))
    }

    fun matches(candidate: String, hash: String?, expiresAt: Instant?): Boolean {
        if (hash == null || expiresAt == null) return false
        if (Instant.now().isAfter(expiresAt)) return false
        return hash(candidate.trim()) == hash
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
