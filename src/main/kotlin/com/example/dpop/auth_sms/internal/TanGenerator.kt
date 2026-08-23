package com.example.dpop.auth_sms.internal

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/** Generates and hashes attempt-scoped TANs; the plaintext never gets persisted. */
internal object TanGenerator {
    private val random = SecureRandom()
    val validity: Duration = Duration.ofMinutes(5)

    data class Issued(val plainTan: String, val hash: String, val expiresAt: Instant)

    fun issue(): Issued {
        val tan = (random.nextInt(900_000) + 100_000).toString()
        return Issued(tan, hash(tan), Instant.now().plus(validity))
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
