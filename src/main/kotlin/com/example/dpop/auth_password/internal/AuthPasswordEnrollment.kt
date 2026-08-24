package com.example.dpop.auth_password.internal

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Long-lived, confirmed password credential (docs/06-ablaeufe.md #1 pattern). Created directly
 * on successful enrollment - a chosen password is self-verifying, unlike a TAN sent out-of-band,
 * so there is no separate "unconfirmed" interim record.
 *
 * No identifier field (no more username): the account's confirmed email (docs/02-domaenenmodell.md
 * #5) is the identifier now, and enroll-password requires it confirmed first
 * (ToolDescriptor.requiresConfirmedEmail) - this module stays as decoupled from `account` as
 * auth_sms already is, referenced only via the generic EnrollmentRef on account.authenticationMethods.
 */
@Entity
@Table(name = "auth_password")
class AuthPasswordEnrollment(
    var passwordHash: String? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
