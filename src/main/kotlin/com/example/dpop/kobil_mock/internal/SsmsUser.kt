package com.example.dpop.kobil_mock.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A user and its bound device, as the foreign system holds them. This row lives in the
 * `kobil_mock` schema precisely so our own tool module cannot read it: the only way to this data
 * is [com.example.dpop.kobil_mock.KobilSsms].
 *
 * That it persists at all is not convenience - an in-memory provider would leave every
 * `auth_kobil.enrollment` pointing at a user that ceased to exist on the last restart.
 */
@Entity
@Table(schema = "kobil_mock", name = "ssms_user")
class SsmsUser(
    @Id
    @Column(name = "user_id", nullable = false)
    var userId: String = "",

    @Column(name = "tenant_id", nullable = false)
    var tenantId: String = "",

    /** Whatever the relying party used to name this subject - opaque to KOBIL. */
    @Column(name = "subject_ref")
    var subjectRef: String? = null,

    @Column(name = "pin")
    var pin: String? = null,

    @Column(name = "activation_code")
    var activationCode: String? = null,

    /** Created by KOBIL on activation, never chosen by the relying party. */
    @Column(name = "device_id")
    var deviceId: String? = null,

    /** Comma-separated [com.example.dpop.kobil_mock.KobilRisk] names; empty means a clean device. */
    @Column(name = "risk_signals", nullable = false)
    var riskSignals: String = "",
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}
