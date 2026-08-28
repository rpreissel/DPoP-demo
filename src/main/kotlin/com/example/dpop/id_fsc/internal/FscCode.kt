package com.example.dpop.id_fsc.internal

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "fsc_code")
class FscCode(
    var personId: Long? = null,

    /**
     * SHA-256 of the code, never the code itself. Unlike the six-digit TANs elsewhere this needs
     * no pepper: a Freischaltcode is not an enumerable number range, and a plain digest is what
     * lets the seeded rows be migrated in SQL (V15).
     */
    var codeHash: String? = null,

    var expiresAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /**
     * Expiry is the only limit: a Freischaltcode is fachlich reusable until it runs out. What
     * bounds GUESSING it is IdentThrottleService, not consumption.
     */
    val isValid: Boolean
        get() = expiresAt != null && Instant.now().isBefore(expiresAt)
}
