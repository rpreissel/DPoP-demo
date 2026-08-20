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
    var code: String? = null,
    var expiresAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    val isValid: Boolean
        get() = expiresAt != null && Instant.now().isBefore(expiresAt)
}
