package com.example.dpop.auth_qr.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * PENDING -> APPROVED (the app approved and showed its confirmation code) -> COMPLETED (the
 * browser entered it). DENIED and EXPIRED end it; so do too many wrong codes (EXPIRED).
 */
enum class QrLoginStatus { PENDING, APPROVED, COMPLETED, DENIED, EXPIRED }

/**
 * The one piece of state connecting a WEB `auth-qr`/`auth-qr-lookup` activation to an APP
 * `confirm-qr-login` decision (docs/05-api.md, Peer-Login bestätigen) - both sides are the same
 * orchestrator process/DB, so no cross-service call is needed to resolve it.
 *
 * [pairingCode] doubles as the primary key: it IS the lookup capability, not just a label
 * (docs/07-betrieb.md #5 - 8 chars, high enough entropy given the short TTL; the anonymous rate
 * limiting on failed lookups this assumes is not yet implemented, see that section).
 *
 * [confirmationCodeHash] is the code in the OPPOSITE direction (review 2026-09, M-2): created when
 * the app approves, shown only there, typed into the browser - only then is the browser logged in.
 * A victim approving an attacker's pairing from a link cannot type into the attacker's browser.
 * Only the hash is kept; [confirmationAttempts] bounds guessing it in the browser.
 *
 * [expectedAccountId] is set only by `auth-qr` (the account is already known via the WEB channel)
 * - `confirm-qr-login`'s approval must match it exactly, never silently take over a different
 * account. Left `null` by `auth-qr-lookup`, which resolves the account itself from
 * [resolvingAccountId].
 */
@Entity
@Table(schema = "auth_qr", name = "login_request")
class QrLoginRequest(
    @Id
    @Column(name = "pairing_code", nullable = false)
    var pairingCode: String? = null,

    @Column(name = "expected_account_id")
    var expectedAccountId: Long? = null
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: QrLoginStatus = QrLoginStatus.PENDING

    @Column(name = "resolving_account_id")
    var resolvingAccountId: Long? = null

    @Column(name = "confirmation_code_hash")
    var confirmationCodeHash: String? = null

    @Column(name = "confirmation_attempts", nullable = false)
    var confirmationAttempts: Int = 0

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
