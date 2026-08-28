package com.example.dpop.auth_email.internal

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
 * Generates and verifies attempt-scoped confirmation codes; the plaintext never gets persisted.
 *
 * The stored value is an HMAC-SHA256 under a server-side pepper, not a bare digest: a six-digit
 * code has only 10^6 possible preimages, so a plain SHA-256 is trivially reversed by anyone who
 * can read `issued_code_hash` - precisely the attacker "not persisted in plaintext" is meant to
 * stop.
 *
 * A deliberate near-duplicate of auth_sms's TanGenerator rather than a shared dependency - leaf
 * modules stay decoupled from each other (docs/08-projektrahmen.md #3), and `internal` in Kotlin
 * is only enforced per compilation module, not per package, so reusing it across auth_sms/
 * auth_email would silently violate the Modulith boundary the two modules are meant to have.
 * That reasoning covers the pepper handling below too: same shape, own copy, own configuration
 * lookup.
 */
@Component
class EmailCodeGenerator(@Value("\${dpop.secrets.otp-pepper:}") configuredPepper: String) {

    /**
     * Blank means a fresh random pepper per boot: safe by default, nothing to check in. A restart
     * then invalidates codes still in flight (they live five minutes), and two instances cannot
     * verify each other's - configure the property for any multi-instance deployment.
     */
    private val pepper: ByteArray = configuredPepper.takeIf { it.isNotBlank() }?.toByteArray()
        ?: ByteArray(32).also { SecureRandom().nextBytes(it) }

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
