package com.example.dpop.auth_sms.internal

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Long-lived, confirmed SMS enrollment (docs/06-ablaeufe.md #1). Exists only after a
 * successful TAN check, so it is valid by definition - no `validated` flag, no `updatedAt`.
 */
@Entity
@Table(name = "auth_sms")
class AuthSmsEnrollment(
    var phoneNumber: String? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
