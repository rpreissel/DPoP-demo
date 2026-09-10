package com.example.dpop.auth_qr.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class QrLoginStatus { PENDING, APPROVED, DENIED, EXPIRED }

/**
 * The one piece of state connecting a WEB `auth-qr`/`auth-qr-lookup` activation to an APP
 * `confirm-qr-login` decision (docs/ideen/qr-login-ueber-app.md #5) - both sides are the same
 * orchestrator process/DB, so no cross-service call is needed to resolve it.
 *
 * [pairingCode] doubles as the primary key: it IS the lookup capability, not just a label
 * (docs/ideen/qr-login-ueber-app.md #6 - 8 chars, high enough entropy given the short TTL and the
 * rate limiting on failed lookups). [verificationCode] is deliberately much lower-entropy - it is
 * never submitted anywhere, only compared by eye between the WEB and APP screens (QR-jacking
 * countermeasure, same doc section).
 *
 * [expectedAccountId] is set only by `auth-qr` (the account is already known via the WEB channel)
 * - `confirm-qr-login`'s approval must match it exactly, never silently take over a different
 * account. Left `null` by `auth-qr-lookup`, which resolves the account itself from
 * [resolvingAccountId].
 */
@Entity
@Table(name = "qr_login_request")
class QrLoginRequest(
    @Id
    @Column(name = "pairing_code", nullable = false)
    var pairingCode: String? = null,

    @Column(name = "verification_code", nullable = false)
    var verificationCode: String? = null,

    @Column(name = "expected_account_id")
    var expectedAccountId: Long? = null
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: QrLoginStatus = QrLoginStatus.PENDING

    @Column(name = "resolving_account_id")
    var resolvingAccountId: Long? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
