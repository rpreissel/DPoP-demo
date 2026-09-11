package com.example.dpop.auth_qr.internal

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * The one long-lived row `enroll-qr` creates - a pure opt-in marker ("this account allows QR
 * login"), never a secret (docs/03-tool-architektur.md). Its only content is its own
 * existence; referenced from `account.authenticationMethods` via the generic `EnrollmentRef`,
 * exactly like every other method's credential row.
 */
@Entity
@Table(name = "auth_qr_optin")
class QrOptIn {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
