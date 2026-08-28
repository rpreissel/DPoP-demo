package com.example.dpop.auth_sms.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Generates and verifies attempt-scoped TANs; the plaintext never gets persisted.
 *
 * The stored value is an HMAC-SHA256 under a server-side pepper, not a bare digest. A bare
 * SHA-256 of a six-digit TAN is not a one-way function in any useful sense - the whole preimage
 * space is 10^6 entries, so anyone who can read `issued_tan_hash` recovers the TAN instantly and
 * the "never persisted in plaintext" property is worth nothing against exactly the attacker it
 * is meant to stop. The pepper is what the attacker with database access does not have.
 *
 * A [Component] rather than an `object` because the pepper has to come from configuration.
 */
@Component
class TanGenerator(@Value("\${dpop.secrets.otp-pepper:}") configuredPepper: String) {

    /**
     * A blank setting means a fresh random pepper per boot: safe by default, no secret to check
     * in and forget. The cost is that a restart invalidates TANs still in flight (they live five
     * minutes) and that two instances cannot verify each other's - configure the property
     * explicitly for any multi-instance deployment.
     */
    private val pepper: ByteArray = configuredPepper.takeIf { it.isNotBlank() }?.toByteArray()
        ?: ByteArray(32).also { SecureRandom().nextBytes(it) }

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
        // Constant-time: `==` on the hex strings leaks how many leading characters matched.
        return MessageDigest.isEqual(hash(candidate.trim()).toByteArray(), hash.toByteArray())
    }

    private fun hash(value: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(pepper, HMAC_ALGORITHM))
        return Base64.getEncoder().encodeToString(mac.doFinal(value.toByteArray()))
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
