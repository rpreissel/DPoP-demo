package com.example.dpop.auth_kobil.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The two secrets this module mints for a device.
 *
 * The **PIN** is KOBIL's, and in this system's deviation from the KOBIL standard flow the user
 * never learns it: the backend keeps it and hands it out per run. That forces one uncomfortable
 * property - it must be stored recoverably, so no hash and no KDF (ADR-22).
 *
 * The **unlock secret** is ours: a high-entropy value the app keeps behind its biometric prompt
 * and presents to get the PIN released. Because it is machine-generated with 256 bits of entropy
 * rather than chosen by a person, a plain SHA-256 is the right store for it and a password KDF
 * would be ceremony - the same reasoning `id_fsc` spells out for its codes. A PBKDF2 round count
 * defends against guessing a small preimage space; there is no small space here.
 */
@Component
class KobilSecrets(
    @Value("\${dpop.kobil.pin-length:8}") private val pinLength: Int,
) {

    private val random = SecureRandom()

    fun newPin(): String = (1..pinLength).map { random.nextInt(10) }.joinToString("")

    fun newUnlockSecret(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun hash(unlockSecret: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(unlockSecret.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * Constant-time, so a wrong secret costs the same as a right one - and a credential whose
     * owner never consented to the biometric path ([storedHash] null) costs the same again
     * instead of answering faster and thereby saying so.
     */
    fun matches(candidate: String, storedHash: String?): Boolean {
        val expected = storedHash ?: NO_CONSENT
        return MessageDigest.isEqual(hash(candidate).toByteArray(), expected.toByteArray()) && storedHash != null
    }

    private companion object {
        /** Never equal to a real digest (hex has no dashes), so the comparison can only fail. */
        const val NO_CONSENT = "-no-biometric-consent-"
    }
}
