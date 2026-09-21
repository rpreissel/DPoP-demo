package com.example.dpop.auth_kobil.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Long-lived, account-bound KOBIL credential.
 *
 * Unlike `auth_device`, the key material is not here and never was: KOBIL holds it. What this row
 * holds is everything needed to (a) decide whether a redeemed assertion belongs to this
 * credential ([kobilDeviceId]), (b) release the PIN to the rightful app ([unlockSecretHash]), and
 * (c) offer the credential only on the installation that owns it ([bindingKeyRef]).
 *
 * [unlockSecretHash] is null unless the user consented to the biometric path; the password is
 * always available, because the method requires it (`EnrollKobilDescriptor.requires`).
 *
 * [pin] is plaintext, demo-only and deliberately so (ADR-22): it has to be handed out, which
 * rules out a hash, and encrypting it under a key sitting in the same configuration would mostly
 * buy the appearance of protection - the mock provider holds the same number anyway, as the real
 * one would.
 */
@Entity
@Table(schema = "auth_kobil", name = "enrollment")
class KobilEnrollment(
    @Column(name = "kobil_tenant_id", nullable = false)
    var kobilTenantId: String = "",

    @Column(name = "kobil_user_id", nullable = false)
    var kobilUserId: String = "",

    /** The device identifier KOBIL created on activation - the anchor a redeemed assertion is compared against. */
    @Column(name = "kobil_device_id", nullable = false)
    var kobilDeviceId: String = "",

    @Column(name = "pin", nullable = false)
    var pin: String = "",

    /**
     * Null when the user did not consent to unlocking by biometrics: the credential then has
     * exactly one way in, the account password. A nullable secret rather than a boolean beside
     * one - "allowed but nothing stored" and "stored but not allowed" are not states this row can
     * be in.
     */
    @Column(name = "unlock_secret_hash")
    var unlockSecretHash: String? = null,

    /** The enrolling channel's DPoP key: what `ToolDescriptor.keyBinding` matches at offer time. */
    @Column(name = "binding_key_ref", nullable = false)
    var bindingKeyRef: String = "",

    @Column(name = "label")
    var label: String? = null,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}
