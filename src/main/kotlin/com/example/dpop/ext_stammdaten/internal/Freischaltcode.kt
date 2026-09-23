package com.example.dpop.ext_stammdaten.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(schema = "ext_stammdaten", name = "freischaltcode")
class Freischaltcode(
    @Column(name = "person_id", nullable = false)
    var personId: Long? = null,

    /**
     * SHA-256 of the code, never the code itself. Unlike the six-digit TANs elsewhere this needs
     * no pepper: a Freischaltcode is not an enumerable number range, and a plain digest is what
     * lets the demo codes be seeded in SQL (demo_seed/V16__testdata.sql).
     */
    @Column(name = "code_hash", nullable = false, length = 64)
    var codeHash: String? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null,

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    /**
     * Expiry and revocation are the only limits: a Freischaltcode is fachlich reusable until it
     * runs out. What bounds GUESSING it is the ident throttle on our side, not consumption.
     */
    fun isValidAt(now: Instant): Boolean =
        revokedAt == null && expiresAt?.let { now.isBefore(it) } == true
}
